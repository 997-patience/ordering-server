package com.wys.orderingserver.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 菜品持久层，对应表 t_dish。 */
@Mapper
public interface DishMapper {

    /** 包括上架、下架菜品；只要仍引用该分类，就不能单独删除分类。 */
    @Select("select count(*) from t_dish where category_id = #{categoryId}")
    long countByCategoryId(Long categoryId);
}
