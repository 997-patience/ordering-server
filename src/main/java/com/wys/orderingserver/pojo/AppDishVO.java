package com.wys.orderingserver.pojo;

import lombok.Data;

import java.math.BigDecimal;

/** 8.3 菜品列表与 8.4 菜品详情返回对象，同时作为列表缓存 JSON 的结构。 */
@Data
public class AppDishVO {
    private Long id;
    private Long storeId;
    private Long categoryId;
    private String name;
    private BigDecimal price;
    private String image;
    private String description;
    private Integer status;
    private Integer sort;

    public static AppDishVO from(Dish dish) {
        AppDishVO result = new AppDishVO();
        result.setId(dish.getId());
        result.setStoreId(dish.getStoreId());
        result.setCategoryId(dish.getCategoryId());
        result.setName(dish.getName());
        result.setPrice(dish.getPrice());
        result.setImage(dish.getImage());
        result.setDescription(dish.getDescription());
        result.setStatus(dish.getStatus());
        result.setSort(dish.getSort());
        return result;
    }
}
