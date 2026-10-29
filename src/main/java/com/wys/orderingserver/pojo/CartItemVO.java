package com.wys.orderingserver.pojo;

import lombok.Data;

import java.math.BigDecimal;

/** 9.1 购物车列表的一项：购物车数量与菜品实时信息的关联查询结果。 */
@Data
public class CartItemVO {
    private Long id;
    private Long dishId;
    private String name;
    private String image;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal amount;
}
