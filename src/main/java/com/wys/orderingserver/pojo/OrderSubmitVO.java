package com.wys.orderingserver.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 提交订单后，仅返回接口约定的三个字段。 */
@Data
@AllArgsConstructor
public class OrderSubmitVO {
    private Long id;
    private String orderNo;
    private BigDecimal amount;
}
