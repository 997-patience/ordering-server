package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 门店            对应表：t_store
 * 全项目的数据隔离维度：所有业务表都通过 store_id 做门店级隔离
 */
@Data
public class Store {

    /** 主键ID（数据库自增，新增时不传） */
    @NotNull(groups = Update.class, message = "门店ID不能为空")
    private Long id;

    /** 门店名称 */
    @NotBlank(message = "门店名称不能为空")
    private String name;

    /** 门店地址 */
    private String address;

    /** 门店电话 */
    private String phone;

    /** 营业开始时间（TIME 列，对应 LocalTime） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime openTime;

    /** 营业结束时间（TIME 列，对应 LocalTime） */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime closeTime;

    /** 门店公告 */
    private String notice;

    /** 状态：1营业中 0已打烊 */
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
