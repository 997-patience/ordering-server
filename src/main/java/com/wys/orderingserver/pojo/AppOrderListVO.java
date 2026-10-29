package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 10.2 我的订单列表；门店名称由订单关联门店表取得。 */
@Data
public class AppOrderListVO {
    private Long id;
    private String orderNo;
    private Long storeId;
    private String storeName;
    private BigDecimal amount;
    private Integer status;
    private Integer pickupType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime pickupTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
