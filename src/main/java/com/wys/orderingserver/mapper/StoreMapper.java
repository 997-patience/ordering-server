package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.Store;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 门店持久层  对应表：t_store
 *
 * 单表查询直接用注解编写 SQL，分页由 PageHelper 处理。
 */
@Mapper
public interface StoreMapper {

    /** 按名称、状态和门店查询列表（参数为空时不限制对应条件） */
    @Select("select * from t_store " +
            "where (#{name} is null or #{name} = '' or name like concat('%', #{name}, '%')) " +
            "and (#{status} is null or status = #{status}) " +
            "and (#{storeId} is null or id = #{storeId}) " +
            "order by id asc")
    List<Store> list(@Param("name") String name,
                    @Param("status") Integer status,
                    @Param("storeId") Long storeId);

    /** 新增门店，数据库自动生成主键并回填到 store.id */
    @Insert("insert into t_store(name, address, phone, open_time, close_time, notice, status, create_time, update_time) " +
            "values(#{name}, #{address}, #{phone}, #{openTime}, #{closeTime}, #{notice}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(Store store);

    /** 删除前锁定门店，防止两个删除请求同时清理同一家店。 */
    @Select("select * from t_store where id = #{id} for update")
    Store findByIdForUpdate(Long id);

    /** 订单明细没有 store_id，通过订单主表找到本店的明细。必须先于订单删除。 */
    @Delete("delete from t_order_detail where order_id in " +
            "(select id from t_orders where store_id = #{storeId})")
    int deleteOrderDetailsByStoreId(Long storeId);

    @Delete("delete from t_orders where store_id = #{storeId}")
    int deleteOrdersByStoreId(Long storeId);

    @Delete("delete from t_cart where store_id = #{storeId}")
    int deleteCartsByStoreId(Long storeId);

    @Delete("delete from t_dish where store_id = #{storeId}")
    int deleteDishesByStoreId(Long storeId);

    @Delete("delete from t_category where store_id = #{storeId}")
    int deleteCategoriesByStoreId(Long storeId);

    @Delete("delete from t_employee where store_id = #{storeId}")
    int deleteEmployeesByStoreId(Long storeId);

    /** 门店最后删除，返回受影响行数。 */
    @Delete("delete from t_store where id = #{id}")
    int delete(Long id);
}
