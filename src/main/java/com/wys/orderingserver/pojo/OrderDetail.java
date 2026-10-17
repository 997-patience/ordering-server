package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细        对应表：t_order_detail
 *
 * ★ 订单快照：dishName / dishImage / dishPrice 三项是下单瞬间从 t_dish 复制过来的副本，
 *   目的是菜品后续改名、改价、下架时，历史订单仍保持原样。
 *   展示订单明细时只读本表的快照字段，绝不 JOIN t_dish。
 */
@Data
public class OrderDetail {

    /** 主键ID（数据库自增，新增时不传） */
    private Long id;

    /** 订单ID */
    @NotNull
    private Long orderId;

    /** 菜品ID（仅用于溯源，不用于展示） */
    @NotNull
    private Long dishId;

    /** ★快照：下单时的菜品名称 */
    @NotEmpty
    private String dishName;

    /** ★快照：下单时的菜品图片 */
    private String dishImage;

    /** ★快照：下单时的菜品单价 */
    @NotNull
    private BigDecimal dishPrice;

    /** 购买数量 */
    @NotNull
    @Min(value = 1, message = "数量至少为1")
    private Integer quantity;

    /** 小计金额 = dishPrice * quantity */
    @NotNull
    private BigDecimal amount;

    /** 创建时间（服务端填充） */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
