package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.Cart;
import com.wys.orderingserver.pojo.CartItemVO;
import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.service.CartService;
import com.wys.orderingserver.utils.UserContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 当前登录顾客的购物车列表、原子加购、数量修改与条目删除。 */
@Service
public class CartServiceimpl implements CartService {
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private StoreMapper storeMapper;
    @Override
    public List<CartItemVO> find(Long storeId) {
        // 用户 ID 来自已验证的登录信息，不接受前端指定要查看哪个用户。
        Long userId = UserContextUtil.getCurrentUserId();
        if (storeId != null && storeId < 1) {
            throw new BizException("请选择有效的门店ID");
        }
        return cartMapper.listByUserIdAndStoreId(userId, storeId);
    }

    @Override
    @Transactional
    public void add(Cart cart) {
        Long userId = UserContextUtil.getCurrentUserId();
        if (cart == null || cart.getDishId() == null || cart.getDishId() < 1) {
            throw new BizException("请选择有效的菜品ID");
        }
        if (cart.getQuantity() == null || cart.getQuantity() < 1) {
            throw new BizException("数量至少为1");
        }

        // 先根据菜品反查门店，再按管理端相同顺序锁定：门店 → 菜品 → 购物车。
        Dish dish = dishMapper.findById(cart.getDishId());
        if (dish == null) {
            throw new BizException("菜品不存在");
        }
        Long storeId = dish.getStoreId();
        if (storeId == null || storeId < 1 || storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        // 锁定后再次检查，避免普通查询之后菜品已被下架或删除。
        dish = dishMapper.findByIdAndStoreIdForUpdate(cart.getDishId(), storeId);
        if (dish == null) {
            throw new BizException("菜品不存在");
        }
        if (!Integer.valueOf(1).equals(dish.getStatus())) {
            throw new BizException("该菜品已下架");
        }

        // 只使用请求中的菜品和数量，其余写库字段都由服务端决定。
        Cart newCart = new Cart();
        newCart.setUserId(userId);
        newCart.setStoreId(storeId);
        newCart.setDishId(dish.getId());
        newCart.setQuantity(cart.getQuantity());
        LocalDateTime now = LocalDateTime.now();
        newCart.setCreateTime(now);
        newCart.setUpdateTime(now);
        int rows;
        try {
            rows = cartMapper.add(newCart);
        } catch (DataIntegrityViolationException e) {
            // 包括 INT 数量累加溢出；转换为可读错误，事务整体回滚。
            throw new BizException("加入购物车失败，请检查数量", e);
        }
        // MySQL：插入通常影响 1 行，重复键后更新通常影响 2 行。
        if (rows != 1 && rows != 2) {
            throw new BizException("加入购物车失败，请重试");
        }
    }

    @Override
    @Transactional
    public void update(Cart cart) {
        Long userId = UserContextUtil.getCurrentUserId();
        if (cart == null || cart.getDishId() == null || cart.getDishId() < 1) {
            throw new BizException("请选择有效的菜品ID");
        }
        if (cart.getQuantity() == null || cart.getQuantity() < 1) {
            throw new BizException("数量至少为1");
        }
        // 9.3 修改已有购物车项，按当前用户 + 菜品定位；不套用 9.2 加购的上下架限制。
        Cart existing = cartMapper.findByUserIdAndDishIdForUpdate(userId, cart.getDishId());
        if (existing == null) {
            throw new BizException("购物车中没有该菜品");
        }
        // 请求的是目标数量，重复提交相同值保持成功，不依赖驱动对未变化 UPDATE 的计数。
        if (cart.getQuantity().equals(existing.getQuantity())) {
            return;
        }
        int rows = cartMapper.update(cart.getDishId(), cart.getQuantity(), userId, LocalDateTime.now());
        if (rows != 1) {
            throw new BizException("购物车数量修改失败，请重试");
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long userId = UserContextUtil.getCurrentUserId();
        if (id == null || id < 1) {
            throw new BizException("请选择有效的购物车项ID");
        }
        // 主键和当前用户在同一条 DELETE 中限定；0 行表示已不存在或不属于本人。
        int rows = cartMapper.deleteByIdAndUserId(id, userId);
        if (rows != 0 && rows != 1) {
            throw new BizException("删除购物车项失败，请重试");
        }
    }
}
