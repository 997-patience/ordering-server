package com.wys.orderingserver.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工（后台管理员）  对应表：t_employee
 */
@Data
public class Employee {

    /** 主键ID（数据库自增，新增时不传） */
    @NotNull(groups = Update.class)
    private Long id;

    /** 登录用户名（唯一） */
    @NotEmpty
    @Pattern(regexp = "^\\S{3,20}$", message = "用户名长度需为3~20个非空白字符")
    private String username;

    /**
     * 密码（MD5 加密后存储）
     * <p>用 WRITE_ONLY 而不是 @JsonIgnore：
     * @JsonIgnore 是双向的，会导致登录/新增员工时也收不到前端传来的 password；
     * WRITE_ONLY 表示"前端可以传给我，但我不会返回给前端"，正好符合需求。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 所属门店ID（超管 role=1 时可为空；门店管理员只操作本店数据） */
    private Long storeId;

    /** 角色：1超管(可管所有门店) 2门店管理员(只看本店) */
    private Integer role;

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
