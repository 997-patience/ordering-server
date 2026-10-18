package com.wys.orderingserver.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.mapper.CategoryMapper;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.service.DishService;
import com.wys.orderingserver.utils.AdminContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class DishServiceimpl implements DishService {
    private static final Logger log = LoggerFactory.getLogger(DishServiceimpl.class);

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private StoreMapper storeMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public PageBean<Dish> page(Integer pageNum, Integer pageSize, String name, Integer status, Long categoryId)
    {
        if (pageNum == null || pageNum < 1) {
            throw new BizException("pageNum必须大于等于1");
        }
        if (pageSize == null || pageSize < 1) {
            throw new BizException("pageSize必须大于等于1");
        }
        if (status != null && status != 0 && status != 1) {
            throw new BizException("菜品状态只能为0或1");
        }
        if (categoryId != null && categoryId < 1) {
            throw new BizException("分类ID必须大于0");
        }
        // 门店范围来自登录信息，必须继续传给 Mapper，成为 SQL 查询条件。
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        String queryName = StringUtils.hasText(name) ? name.trim() : null;

        // 只开启一次分页，下一条 MyBatis 查询就是本门店的菜品列表。
        try (Page<Dish> page = PageHelper.startPage(pageNum, pageSize)) {
            dishMapper.list(queryName, status, categoryId, storeId);
            return new PageBean<>(page.getTotal(), page.getResult());
        }
    }

    @Override
    @Transactional
    public void addDish(Dish dish) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        validateDishFields(dish);
        if (dish.getStatus() == null) {
            dish.setStatus(1);
        }
        if (dish.getSort() == null) {
            dish.setSort(0);
        }

        // 与分类删除、整店删除按相同顺序加锁，校验和插入处于同一事务。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        if (categoryMapper.findByIdAndStoreIdForUpdate(dish.getCategoryId(), storeId) == null) {
            throw new BizException("分类不存在或不属于当前门店");
        }

        // 新增记录的归属、主键和时间由服务端决定，图片地址允许不传。
        dish.setId(null);
        dish.setStoreId(storeId);
        dish.setImage(StringUtils.hasText(dish.getImage()) ? dish.getImage().trim() : null);
        LocalDateTime now = LocalDateTime.now();
        dish.setCreateTime(now);
        dish.setUpdateTime(now);
        if (dishMapper.addDish(dish) != 1) {
            throw new BizException("新增菜品失败，请重试");
        }
    }

    @Override
    @Transactional
    public void updateDish(Dish dish) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        if (dish == null || dish.getId() == null || dish.getId() < 1) {
            throw new BizException("请选择有效的菜品ID");
        }
        validateDishFields(dish);
        // 与新增菜品、分类删除、整店删除保持相同的加锁顺序。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        if (categoryMapper.findByIdAndStoreIdForUpdate(dish.getCategoryId(), storeId) == null) {
            throw new BizException("分类不存在或不属于当前门店");
        }

        // 修改必须保留目标 ID；不修改创建时间，不填新增时的状态和排序默认值。
        dish.setStoreId(storeId);
        dish.setUpdateTime(LocalDateTime.now());
        if (dish.getImage() != null) {
            dish.setImage(dish.getImage().trim());
        }
        if (dishMapper.updateDish(dish) != 1) {
            throw new BizException("菜品不存在或不属于当前门店");
        }
        clearDishCacheAfterCommit(storeId);
    }

    @Override
    @Transactional
    public void updateDishStatus(Long id, Integer status) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        if (id == null || id < 1) {
            throw new BizException("请选择有效的菜品ID");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("菜品状态只能为0或1");
        }
        // 与修改菜品、整店删除保持相同顺序：先锁门店，再锁目标菜品。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        Dish dish = dishMapper.findByIdAndStoreIdForUpdate(id, storeId);
        if (dish == null) {
            throw new BizException("菜品不存在或不属于当前门店");
        }
        // status 是目标状态；重复请求保持成功，避免把“下架”误做成“来回切换”。
        if (!status.equals(dish.getStatus())
                && dishMapper.updateDishStatus(id, status, storeId, LocalDateTime.now()) != 1) {
            throw new BizException("菜品状态修改失败，请重试");
        }
        clearDishCacheAfterCommit(storeId);
    }

    @Override
    @Transactional
    public void deleteDish(Long id) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        if (id == null || id < 1) {
            throw new BizException("请选择有效的菜品ID");
        }
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        if (dishMapper.findByIdAndStoreIdForUpdate(id, storeId) == null) {
            throw new BizException("菜品不存在或不属于当前门店");
        }

        // 先清理购物车引用，再删菜品；两步处于同一事务，订单快照保持不变。
        cartMapper.deleteByDishIdAndStoreId(id, storeId);
        int rows = dishMapper.deleteDish(id, storeId);
        if (rows != 1) {
            throw new BizException("删除菜品失败，请重试");
        }
        clearDishCacheAfterCommit(storeId);
    }

    /** 新增和修改共用字段校验；默认值、目标 ID 等差异由各自业务处理。 */
    private void validateDishFields(Dish dish) {
        if (dish == null || !StringUtils.hasText(dish.getName())) {
            throw new BizException("菜品名称不能为空");
        }
        String name = dish.getName().trim();
        if (name.codePointCount(0, name.length()) > 100) {
            throw new BizException("菜品名称不能超过100个字符");
        }
        if (dish.getCategoryId() == null || dish.getCategoryId() < 1) {
            throw new BizException("请选择有效的菜品分类");
        }
        if (dish.getPrice() == null || dish.getPrice().signum() < 0) {
            throw new BizException("菜品价格不能为空且不能为负数");
        }
        if (dish.getPrice().stripTrailingZeros().scale() > 2
                || dish.getPrice().compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new BizException("菜品价格最多8位整数和2位小数");
        }
        if (dish.getStatus() != null && dish.getStatus() != 0 && dish.getStatus() != 1) {
            throw new BizException("菜品状态只能为0或1");
        }
        if (dish.getDescription() != null && dish.getDescription().codePointCount(
                0, dish.getDescription().length()) > 500) {
            throw new BizException("菜品描述不能超过500个字符");
        }
        if (dish.getImage() != null && dish.getImage().codePointCount(0, dish.getImage().length()) > 255) {
            throw new BizException("图片地址不能超过255个字符");
        }
        dish.setName(name);
        dish.setPrice(dish.getPrice().setScale(2));
    }

    /** 数据库事务提交后才删除本店全部分类的列表缓存，覆盖修改分类和全部菜品列表。 */
    private void clearDishCacheAfterCommit(Long storeId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ScanOptions options = ScanOptions.scanOptions().match("dish:" + storeId + ":*").count(200).build();
                try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                    List<String> keys = new ArrayList<>();
                    while (cursor.hasNext()) {
                        keys.add(cursor.next());
                        if (keys.size() == 200) {
                            stringRedisTemplate.delete(keys);
                            keys.clear();
                        }
                    }
                    if (!keys.isEmpty()) {
                        stringRedisTemplate.delete(keys);
                    }
                } catch (RuntimeException e) {
                    // 数据库此时已提交，记录缓存故障，避免把已成功的修改误报成写库失败。
                    log.error("菜品数据已变更，清理门店 {} 的菜品列表缓存失败", storeId, e);
                }
            }
        });
    }
}
