package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（顾客）      对应表：t_user
 * 字段与列名一一对应（驼峰映射：phone/phone、create_time --> createTime）
 */
@Data
public class User {

    /** 主键ID（数据库自增，新增时不传） */
    @NotNull(groups = Update.class)
    private Long id;

    /** 手机号（登录账号，唯一） */
    @NotEmpty
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 密码（MD5 加密后存储，序列化时不返回给前端） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "密码不能为空", groups = Add.class)
    @Pattern(
            regexp = "^\\S{5,16}$",
            message = "密码必须为5~16位非空白字符",
            groups = Add.class
    )
    private String password;

    /** 昵称 */
    @NotEmpty
    @Pattern(regexp = "^\\S{1,16}$", message = "昵称长度需为1~16个非空白字符")
    private String nickname;

    /** 头像地址 */
    private String avatar;

    /** 创建时间（服务端填充，不接受前端传入） */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 更新时间（服务端填充，不接受前端传入） */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    //分组校验：新增 / 修改（嵌套在实体内部，与 bigevent 的 Category.Add/Update 一致）
    public interface Add extends Default {
    }

    public interface Update extends Default {
    }
}
