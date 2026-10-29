package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.pojo.AppDishVO;
import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.service.AppDishService;
import com.wys.orderingserver.utils.DishCacheUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** 用户端菜品列表与详情：参数检查、列表缓存和详情查询。 */
@Service
public class AppDishServiceimpl implements AppDishService {
    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private DishCacheUtil dishCacheUtil;

    @Override
    public List<AppDishVO> find(Long storeId, Long categoryId) {
        if (storeId == null || storeId < 1) {
            throw new BizException("请选择有效的门店ID");
        }
        if (categoryId != null && categoryId < 1) {
            throw new BizException("请选择有效的分类ID");
        }
        // 原 SQL 已支持分类为 null：不传分类时返回本店全部上架菜品。
        // 只有缓存未命中且取得互斥锁时，缓存工具才调用这段查询。
        return dishCacheUtil.getOrLoad(storeId, categoryId,
                () -> dishMapper.list(null, 1, categoryId, storeId).stream()
                        .map(AppDishVO::from).toList());
    }

    @Override
    public AppDishVO getDetail(Long id) {
        if (id == null || id < 1) {
            throw new BizException("请选择有效的菜品ID");
        }
        // 8.4 按菜品主键查询单个对象，文档允许返回上架或下架状态。
        Dish dish = dishMapper.findById(id);
        if (dish == null) {
            throw new BizException("菜品不存在");
        }
        return AppDishVO.from(dish);
    }
}
