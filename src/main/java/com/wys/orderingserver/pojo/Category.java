package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜品分类        对应表：t_category
 * 分类归属门店：不同门店可以有同名分类，查询必须带 store_id
 */
@Data
public class Category {

    /** 主键ID（数据库自增，新增时不传） */
    @NotNull(groups = Update.class)
    private Long id;

    /** 所属门店ID（数据隔离字段；新增时由服务端从登录信息填充） */
    private Long storeId;

    /** 分类名称 */
    @NotBlank(message = "分类名称不能为空")
    private String name;

    /** 排序号，越小越靠前 */
    private Integer sort;

    /** 状态：1启用 0禁用 */
    private Integer status;

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
