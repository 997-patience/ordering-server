package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {

    /** 查询本店订单；beginTime、endTime 都是下单时间 create_time 的边界，包含端点。 */
    @Select("select * from t_orders " +
            "where store_id = #{storeId} " +
            "and (#{status} is null or status = #{status}) " +
            "and (#{beginTime} is null or create_time >= #{beginTime}) " +
            "and (#{endTime} is null or create_time <= #{endTime}) " +
            "order by id asc")
    List<Order> list(@Param("storeId") Long storeId,
              @Param("status") Integer status,
              @Param("beginTime") LocalDateTime beginTime,
              @Param("endTime") LocalDateTime endTime);

    /** 只锁定当前门店的目标订单，供订单状态流转使用。 */
    @Select("select * from t_orders where id = #{id} and store_id = #{storeId} for update")
    Order findByIdAndStoreIdForUpdate(@Param("id") Long id, @Param("storeId") Long storeId);

    /** 条件更新：只有本门店仍处于待接单状态的订单，才能变为已接单。 */
    @Update("update t_orders set status = 2, update_time = #{updateTime} " +
            "where id = #{id} and store_id = #{storeId} and status = 1")
    int accept(@Param("id") Long id,
               @Param("storeId") Long storeId,
               @Param("updateTime") LocalDateTime updateTime);

    /** 本门店已接单的订单才能完成，三个字段在同一条 SQL 中更新。 */
    @Update("update t_orders set status = 3, update_time = #{completeTime}, finish_time = #{completeTime} " +
            "where id = #{id} and store_id = #{storeId} and status = 2")
    int complete(@Param("id") Long id,
                 @Param("storeId") Long storeId,
                 @Param("completeTime") LocalDateTime completeTime);

    /** 只有本门店待接单的订单可以拒单；拒单不改写完成时间和支付信息。 */
    @Update("update t_orders set status = 4, cancel_reason = #{cancelReason}, update_time = #{updateTime} " +
            "where id = #{id} and store_id = #{storeId} and status = 1")
    int cancel(@Param("id") Long id,
               @Param("storeId") Long storeId,
               @Param("cancelReason") String cancelReason,
               @Param("updateTime") LocalDateTime updateTime);

    /** 今日下单数量：按当前状态分类，status 为 null 时统计全部状态。 */
    @Select("select count(*) from t_orders " +
            "where store_id = #{storeId} and create_time >= #{beginTime} and create_time < #{endTime} " +
            "and (#{status} is null or status = #{status})")
    Long countByCreateTime(@Param("storeId") Long storeId,
                           @Param("status") Integer status,
                           @Param("beginTime") LocalDateTime beginTime,
                           @Param("endTime") LocalDateTime endTime);

    /** 今日营业额：按完成时间汇总本门店已完成订单，空集合返回 0。 */
    @Select("select coalesce(sum(amount), 0) from t_orders " +
            "where store_id = #{storeId} and status = 3 " +
            "and finish_time >= #{beginTime} and finish_time < #{endTime}")
    BigDecimal sumTurnover(@Param("storeId") Long storeId,
                          @Param("beginTime") LocalDateTime beginTime,
                          @Param("endTime") LocalDateTime endTime);
}
