package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.Order;
import com.wys.orderingserver.pojo.AppOrderListVO;
import com.wys.orderingserver.pojo.AppOrderDetailVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {

    /** 本人跨门店订单；先过滤用户和状态，再分页，同秒订单按主键倒序稳定排列。 */
    @Select("select o.id, o.order_no, o.store_id, s.name as store_name, o.amount, o.status, " +
            "o.pickup_type, o.pickup_time, o.create_time " +
            "from t_orders o left join t_store s on s.id = o.store_id " +
            "where o.user_id = #{userId} " +
            "and (#{status} is null or o.status = #{status}) " +
            "order by o.create_time desc, o.id desc")
    List<AppOrderListVO> listByUserId(@Param("userId") Long userId, @Param("status") Integer status);

    /** 主键回填后，Service 才能把订单 ID 写入各条明细。 */
    @Insert("insert into t_orders(order_no, user_id, store_id, amount, status, pay_status, " +
            "remark, pickup_type, pickup_time, create_time, update_time) " +
            "values(#{orderNo}, #{userId}, #{storeId}, #{amount}, #{status}, #{payStatus}, " +
            "#{remark}, #{pickupType}, #{pickupTime}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    /** 扫描所有门店；仅取消仍待接单且已满 15 分钟的订单，不覆盖已接单等状态。 */
    @Update("update t_orders set status = 4, cancel_reason = '商家超时未接单，订单自动取消', " +
            "update_time = #{now} where status = 1 and create_time <= #{deadline}")
    int cancelTimedOut(@Param("deadline") LocalDateTime deadline, @Param("now") LocalDateTime now);

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

    /** 详情主信息必须属于当前顾客，门店名称按文档关联查询。 */
    @Select("select o.id, o.order_no, o.store_id, s.name as store_name, o.amount, o.status, " +
            "o.pay_status, o.remark, o.pickup_type, o.pickup_time, o.cancel_reason, " +
            "o.create_time, o.finish_time " +
            "from t_orders o left join t_store s on s.id = o.store_id " +
            "where o.id = #{id} and o.user_id = #{userId}")
    AppOrderDetailVO getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 用户取消前锁定本人的订单，用当前状态作判断，不关联或锁定其他业务表。 */
    @Select("select * from t_orders where id = #{id} and user_id = #{userId} for update")
    Order findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /** 只有本人仍待接单的订单可以取消，保留支付、完成时间和明细等其他数据。 */
    @Update("update t_orders set status = 4, cancel_reason = '用户主动取消', update_time = #{updateTime} " +
            "where id = #{id} and user_id = #{userId} and status = 1")
    int cancelByUser(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("updateTime") LocalDateTime updateTime);
}
