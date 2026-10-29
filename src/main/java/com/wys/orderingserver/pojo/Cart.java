package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车          对应表：t_cart
 * 表上有唯一索引 uk_user_dish(user_id, dish_id)：
 * 重复加购靠 INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity) 原子累加，
 * 不能只靠前端判断（并发下会插两行）
 */
@Data
public class Cart {

    /** 主键ID由数据库生成；9.3 按当前用户与菜品定位，不要求前端传入主键。 */
    private Long id;

    /** 用户ID（从登录信息 ThreadLocal 取，绝不从请求体接收） */
    private Long userId;

    /** 门店ID（冗余字段，便于按门店清空购物车） */
    private Long storeId;

    /** 菜品ID */
    @NotNull(message = "菜品ID不能为空")
    @Min(value = 1, message = "请选择有效的菜品ID")
    private Long dishId;

    /** 数量 */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为1")
    private Integer quantity;

    /** 创建时间（服务端填充） */
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
