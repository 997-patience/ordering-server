package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.pojo.PageBean;

public interface DishService {
    //分页查询本门店的菜品，支持按名称、分类、上架状态筛选
    PageBean<Dish> page(Integer pageNum, Integer pageSize, String name, Integer status, Long categoryId);
    //新增菜品
    void addDish(Dish dish);

    void updateDish(Dish dish);
    //切换菜品的上架/下架状态
    void updateDishStatus(Long id, Integer status);
    //删除
    void deleteDish(Long id);
}
