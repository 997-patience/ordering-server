package com.wys.orderingserver.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 购物车持久层，对应表 t_cart。 */
@Mapper
public interface CartMapper {

    /** 删除菜品时清理本店购物车引用，避免保留无法购买的条目。 */
    @Delete("delete from t_cart where dish_id = #{dishId} and store_id = #{storeId}")
    int deleteByDishIdAndStoreId(@Param("dishId") Long dishId, @Param("storeId") Long storeId);
}
