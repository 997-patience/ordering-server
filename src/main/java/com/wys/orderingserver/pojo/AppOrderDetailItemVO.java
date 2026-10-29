package com.wys.orderingserver.pojo;

import lombok.Data;

import java.math.BigDecimal;

/** 10.3 的单条菜品明细；字段全部取自下单时保存的订单快照。 */
@Data
public class AppOrderDetailItemVO {
    private Long dishId;
    private String dishName;
    private String dishImage;
    private BigDecimal dishPrice;
    private Integer quantity;
    private BigDecimal amount;
}
