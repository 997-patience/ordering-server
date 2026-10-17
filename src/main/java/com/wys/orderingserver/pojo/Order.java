package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表        对应表：t_orders
 * （类名用 Order 而不是 Orders：Java 类名不冲突，"order" 只是 SQL 关键字，
 *   建表用了 t_orders 避开，实体类名保持单数更自然）
 *
 * 状态机：1待接单 --> 2已接单 --> 3已完成 ／ 4已取消
 * 状态只能单向前进，流转校验写在 Service 层
 */
@Data
public class Order {

    /** 主键ID（数据库自增，新增时不传） */
    @NotNull(groups = Update.class)
    private Long id;

    /** 订单号（业务唯一，展示给用户；由服务端生成） */
    private String orderNo;

    /** 下单用户ID（从登录信息取） */
    private Long userId;

    /** 门店ID（商家端只查本店订单） */
    private Long storeId;

    /** 订单总金额（元）；下单时算好落库，不实时汇总 */
    @NotNull
    @DecimalMin(value = "0.00", message = "订单金额不能为负数")
    private BigDecimal amount;

    /** 订单状态：1待接单 2已接单 3已完成 4已取消 */
    private Integer status;

    /** 支付状态：0未支付 1已支付（模拟支付） */
    private Integer payStatus;

    /** 支付时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime payTime;

    /** 订单备注（如：不要香菜） */
    private String remark;

    /** 取餐方式：1到店自取 2堂食扫码 */
    private Integer pickupType;

    /** 预约取餐时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime pickupTime;

    /** 取消/拒单原因（拒单时必填） */
    private String cancelReason;

    /** 完成时间（今日看板统计营业额按此字段统计，口径才正确） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    /** 下单时间（服务端填充） */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 更新时间（服务端填充） */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    //分组校验：新增 / 修改
    public interface Add extends Default {
    }

    public interface Update extends Default {
    }
}
