package com.wys.orderingserver.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.wys.orderingserver.pojo.OrderDetail;
import com.wys.orderingserver.pojo.AppOrderDetailItemVO;

import java.util.List;

/** 订单明细持久层：保存下单快照，历史展示应读取本表。 */
@Mapper
public interface OrderDetailMapper {
    /** 使用 order_id 查询全部明细，并再次限定订单所属用户；历史信息不关联菜品表。 */
    @Select("select d.dish_id, d.dish_name, d.dish_image, d.dish_price, d.quantity, d.amount " +
            "from t_order_detail d inner join t_orders o on o.id = d.order_id " +
            "where d.order_id = #{orderId} and o.user_id = #{userId} " +
            "order by d.id asc")
    List<AppOrderDetailItemVO> listByOrderIdAndUserId(@Param("orderId") Long orderId,
                                                  @Param("userId") Long userId);

    @Insert("insert into t_order_detail(order_id, dish_id, dish_name, dish_image, dish_price, " +
            "quantity, amount, create_time) values(#{orderId}, #{dishId}, #{dishName}, " +
            "#{dishImage}, #{dishPrice}, #{quantity}, #{amount}, #{createTime})")
    int insert(OrderDetail detail);
}
