package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.Employee;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 员工（后台管理员）持久层  对应表：t_employee
 *
 * 说明：单表 CRUD 用注解写在接口上，多表关联等复杂查询才写到
 * resources/com/wys/orderingserver/mapper/EmployeeMapper.xml（namespace 与本接口全限定名一致）。
 */
@Mapper
public interface EmployeeMapper {

    /** 新增员工（主键自增，用 useGeneratedKeys 回填 id） */
    @Insert("insert into t_employee(username, password, name, phone, store_id, role, status, create_time, update_time) " +
            "values(#{username}, #{password}, #{name}, #{phone}, #{storeId}, #{role}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(Employee employee);

    /** 按用户名查询（登录用，用户名上有唯一索引 uk_username） */
    @Select("select * from t_employee where username = #{username}")
    Employee findByUsername(String username);

    /** 按主键查询（获取当前登录员工信息用） */
    @Select("select * from t_employee where id = #{id}")
    Employee findById(Long id);

    /** 按门店查询员工列表 */
    @Select("select * from t_employee where store_id = #{storeId} order by id")
    List<Employee> listByStoreId(Long storeId);
}
