package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 菜品            对应表：t_dish
 * 金额字段用 BigDecimal，绝不用 double（二进制浮点无法精确表示 0.1）
 */
@Data
public class Dish {

    /** 主键ID（数据库自增，新增时不传） */
    @NotNull(groups = Update.class)
    private Long id;

    /** 所属门店ID（数据隔离字段；新增时由服务端从登录信息填充） */
    private Long storeId;

    /** 所属分类ID */
    @NotNull
    private Long categoryId;

    /** 菜品名称 */
    @NotEmpty
    private String name;

    /** 价格（元），对应 DECIMAL(10,2) */
    @NotNull
    @DecimalMin(value = "0.00", message = "价格不能为负数")
    private BigDecimal price;

    /** 图片地址（相对路径，如 /upload/xxx.jpg） */
    private String image;

    /** 菜品描述 */
    private String description;

    /** 状态：1上架 0下架 */
    private Integer status;

    /** 排序号，越小越靠前 */
    private Integer sort;

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
