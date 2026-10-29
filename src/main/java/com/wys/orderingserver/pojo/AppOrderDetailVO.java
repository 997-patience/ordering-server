package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 10.3 订单详情：一份订单主表信息，加多条历史菜品快照。 */
@Data
public class AppOrderDetailVO {
    private Long id;
    private String orderNo;
    private Long storeId;
    private String storeName;
    private BigDecimal amount;
    private Integer status;
    private Integer payStatus;
    private String remark;
    private Integer pickupType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime pickupTime;

    private String cancelReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    private List<AppOrderDetailItemVO> details = new ArrayList<>();
}
