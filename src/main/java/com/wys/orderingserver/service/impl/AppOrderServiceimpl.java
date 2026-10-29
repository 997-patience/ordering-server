package com.wys.orderingserver.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.mapper.OrderDetailMapper;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.*;
import com.wys.orderingserver.service.AppOrderService;
import com.wys.orderingserver.utils.UserContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 当前顾客下单，以及按用户范围分页查询本人订单。 */
@Service
public class AppOrderServiceimpl implements AppOrderService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderDetailMapper orderDetailMapper;
    @Autowired private CartMapper cartMapper;
    @Autowired private DishMapper dishMapper;
    @Autowired private StoreMapper storeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmitVO submit(OrderSubmitDTO request) {
        Long userId = UserContextUtil.getCurrentUserId();
        validateRequest(request);
        Long storeId = request.getStoreId();

        // 管理端改菜、删店、9.2 加购也先锁门店，因此不会在下单中途改价或插入新购物车项。
        Store store = storeMapper.findByIdForUpdate(storeId);
        if (store == null) {
            throw new BizException("门店不存在");
        }
        if (!Integer.valueOf(1).equals(store.getStatus())) {
            throw new BizException("门店已打烊，暂时无法下单");
        }

        List<Cart> carts = cartMapper.listForUpdate(userId, storeId);
        if (carts.isEmpty()) {
            throw new BizException("购物车为空，无法下单");
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        List<OrderDetail> details = new ArrayList<>();
        BigDecimal total = new BigDecimal("0.00");
        for (Cart cart : carts) {
            // 不用 Redis 菜品缓存计价：从数据库复制本次下单的实时菜品信息。
            // 菜品写操作已被门店锁阻挡，这里无需在持有购物车锁后再加菜品写锁。
            Dish dish = dishMapper.findById(cart.getDishId());
            if (dish == null || !storeId.equals(dish.getStoreId())) {
                throw new BizException("购物车中的菜品不存在或不属于当前门店");
            }
            if (!Integer.valueOf(1).equals(dish.getStatus())) {
                throw new BizException("购物车中存在已下架菜品，请先移除后再下单");
            }
            if (cart.getQuantity() == null || cart.getQuantity() < 1) {
                throw new BizException("购物车数量异常，请重新添加菜品");
            }
            BigDecimal price = validPrice(dish.getPrice());
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(cart.getQuantity()));
            total = total.add(subtotal);
            if (total.compareTo(MAX_AMOUNT) > 0) {
                throw new BizException("订单金额超出允许范围，请减少购买数量");
            }

            OrderDetail detail = new OrderDetail();
            detail.setDishId(dish.getId());
            detail.setDishName(dish.getName());
            detail.setDishImage(dish.getImage());
            detail.setDishPrice(price);
            detail.setQuantity(cart.getQuantity());
            detail.setAmount(subtotal);
            detail.setCreateTime(now);
            details.add(detail);
        }

        Order order = new Order();
        // UUID 生成 32 位订单号，不使用毫秒时间戳充当唯一号；数据库唯一索引再作约束。
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setStoreId(storeId);
        order.setAmount(total);
        order.setStatus(1);
        order.setPayStatus(0);
        order.setRemark(request.getRemark());
        order.setPickupType(request.getPickupType());
        order.setPickupTime(request.getPickupTime());
        order.setCreateTime(now);
        order.setUpdateTime(now);
        if (orderMapper.insert(order) != 1 || order.getId() == null) {
            throw new BizException("提交订单失败，请重试");
        }
        for (OrderDetail detail : details) {
            // 主表 INSERT 的自增主键已回填，所有明细使用同一个订单 ID。
            detail.setOrderId(order.getId());
            if (orderDetailMapper.insert(detail) != 1) {
                throw new BizException("保存订单明细失败，请重试");
            }
        }
        if (cartMapper.deleteByUserIdAndStoreId(userId, storeId) != carts.size()) {
            throw new BizException("清空购物车失败，请重试");
        }
        return new OrderSubmitVO(order.getId(), order.getOrderNo(), order.getAmount());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageBean<AppOrderListVO> page(Integer pageNum, Integer pageSize, Integer status) {
        if (pageNum == null || pageNum < 1) {
            throw new BizException("pageNum必须大于等于1");
        }
        if (pageSize == null || pageSize < 1) {
            throw new BizException("pageSize必须大于等于1");
        }
        if (status != null && (status < 1 || status > 4)) {
            throw new BizException("订单状态只能为1、2、3或4");
        }
        // 顾客可查看本人在所有门店的订单；用户 ID 来自登录信息，不能套用管理端门店权限。
        Long userId = UserContextUtil.getCurrentUserId();

        // 立即执行目标查询；沿用项目 reasonable=true 配置，越过末页时返回最后一页。
        // 只读可重复读事务让 count 与当前页查询使用同一快照，close() 清理分页上下文。
        try (Page<AppOrderListVO> page = PageHelper.startPage(pageNum, pageSize)) {
            orderMapper.listByUserId(userId, status);
            return new PageBean<>(page.getTotal(), page.getResult());
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AppOrderDetailVO getById(Long id) {
        Long userId = UserContextUtil.getCurrentUserId();
        if (id == null || id < 1) {
            throw new BizException("请传入合法的订单ID");
        }
        // 路径中的 id 是订单主键；SQL 同时限定登录用户，避免修改路径查看他人订单。
        AppOrderDetailVO order = orderMapper.getByIdAndUserId(id, userId);
        if (order == null) {
            // 不区分不存在与他人订单，不向调用者泄露其他订单是否存在。
            throw new BizException("订单不存在或无权访问");
        }
        // 主表确认归属后，再查询该订单的全部快照；空明细返回 []。
        order.setDetails(orderDetailMapper.listByOrderIdAndUserId(id, userId));
        return order;
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        Long userId = UserContextUtil.getCurrentUserId();
        if (id == null || id < 1) {
            throw new BizException("请输入合法的订单ID");
        }
        // 只锁定本人的目标订单，取得最新状态；随后仅更新同一行，不再申请门店锁。
        Order order = orderMapper.findByIdAndUserIdForUpdate(id, userId);
        if (order == null) {
            throw new BizException("订单不存在或无权访问");
        }
        if (Integer.valueOf(2).equals(order.getStatus())) {
            throw new BizException("商家已接单，无法取消，请联系门店");
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            throw new BizException("当前订单状态不允许取消");
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        // SQL 同时限定主键、用户和待接单状态；原因由服务端固定，影响行数必须为 1。
        if (orderMapper.cancelByUser(id, userId, now) != 1) {
            throw new BizException("取消订单失败，请重试");
        }
    }

    private void validateRequest(OrderSubmitDTO request) {
        // 同时保护非 HTTP 的 Service 调用，避免只依赖 Controller 的 @Valid。
        if (request == null || request.getStoreId() == null || request.getStoreId() < 1) {
            throw new BizException("请选择有效的门店ID");
        }
        if (request.getPickupType() == null
                || (request.getPickupType() != 1 && request.getPickupType() != 2)) {
            throw new BizException("取餐方式只能为1或2");
        }
        if (request.getRemark() != null && request.getRemark().length() > 255) {
            throw new BizException("订单备注不能超过255个字符");
        }
    }

    private BigDecimal validPrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_AMOUNT) > 0) {
            throw new BizException("菜品价格异常，暂时无法下单");
        }
        try {
            return price.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BizException("菜品价格异常，暂时无法下单", e);
        }
    }
}
