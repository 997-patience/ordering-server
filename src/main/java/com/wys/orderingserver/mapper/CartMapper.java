package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.Cart;
import com.wys.orderingserver.pojo.CartItemVO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/** 购物车持久层，对应表 t_cart。 */
@Mapper
public interface CartMapper {

    /** 下单时锁定本人、本店购物车；普通 JOIN 列表无法发现孤立或门店错误的条目。 */
    @Select("select * from t_cart where user_id = #{userId} and store_id = #{storeId} " +
            "order by id asc for update")
    List<Cart> listForUpdate(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /** 只能在订单主表及全部明细保存成功后，清理当前用户、本店的购物车。 */
    @Delete("delete from t_cart where user_id = #{userId} and store_id = #{storeId}")
    int deleteByUserIdAndStoreId(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /** 删除菜品时清理本店购物车引用，避免保留无法购买的条目。 */
    @Delete("delete from t_cart where dish_id = #{dishId} and store_id = #{storeId}")
    int deleteByDishIdAndStoreId(@Param("dishId") Long dishId, @Param("storeId") Long storeId);

    /** 始终限定当前用户；门店可选，菜品关联同时核对门店归属，小计使用 DECIMAL 运算。 */
    @Select("select c.id, c.dish_id, d.name, d.image, d.price, c.quantity, " +
            "d.price * c.quantity as amount " +
            "from t_cart c inner join t_dish d on d.id = c.dish_id and d.store_id = c.store_id " +
            "where c.user_id = #{userId} " +
            "and (#{storeId} is null or c.store_id = #{storeId}) " +
            "order by c.id asc")
    List<CartItemVO> listByUserIdAndStoreId(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /** 唯一索引(user_id, dish_id)冲突时原子累加数量，保留原主键和创建时间。 */
    @Insert("insert into t_cart(user_id, store_id, dish_id, quantity, create_time, update_time) " +
            "values(#{userId}, #{storeId}, #{dishId}, #{quantity}, #{createTime}, #{updateTime}) " +
            "on duplicate key update quantity = quantity + values(quantity), " +
            "update_time = values(update_time)")
    int add(Cart cart);


    /** 先按当前用户及菜品定位并锁定已有条目；不查询或锁定其他用户的条目。 */
    @Select("select * from t_cart where user_id = #{userId} and dish_id = #{dishId} for update")
    Cart findByUserIdAndDishIdForUpdate(@Param("userId") Long userId, @Param("dishId") Long dishId);

    /** 设置目标数量，始终限定当前用户，不改变归属与创建时间。 */
    @Update("update t_cart set quantity = #{quantity}, update_time = #{updateTime} " +
            "where dish_id = #{dishId} and user_id = #{userId}")
    int update(@Param("dishId") Long dishId, @Param("quantity") Integer quantity,
               @Param("userId") Long userId, @Param("updateTime") LocalDateTime updateTime);

    /** 用户端按购物车主键删除本人条目，与管理端按菜品清理全店引用分开。 */
    @Delete("delete from t_cart where id = #{id} and user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
