package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.User;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 顾客数据访问入口，对应 t_user；SQL 留到用户端章节实现。 */
@Mapper
public interface UserMapper {
    //
    @Select("select * from t_user where phone = #{phone}")
    User findByPhone(@Param("phone") String phone);
    //添加用户
    @Insert("insert into t_user " +
            "(phone, password, nickname, create_time, update_time) " +
            "values " +
            "(#{phone}, #{password}, #{nickname}, #{createTime}, #{updateTime})")
    int add(User user);

}
