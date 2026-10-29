package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** 10.1 只接收顾客能填写的字段；金额、用户、订单状态等字段由服务端决定。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderSubmitDTO {
    @NotNull(message = "门店ID不能为空")
    @Min(value = 1, message = "请选择有效的门店ID")
    private Long storeId;

    @Size(max = 255, message = "订单备注不能超过255个字符")
    private String remark;

    @NotNull(message = "取餐方式不能为空")
    @Min(value = 1, message = "取餐方式只能为1或2")
    @Max(value = 2, message = "取餐方式只能为1或2")
    private Integer pickupType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime pickupTime;
}
