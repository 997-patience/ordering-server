package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.AppDishVO;

import java.util.List;

/** 用户端菜品浏览业务，对应文档 8.3～8.4。 */
public interface AppDishService {
    //查询指定门店、指定分类下的**上架**菜品
    List<AppDishVO> find(Long storeId, Long categoryId);
    //根据 ID 查询菜品详情
    AppDishVO getDetail(Long id);
}
