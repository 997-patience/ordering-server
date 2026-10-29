package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.Dish;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/** 菜品持久层，对应表 t_dish。 */
@Mapper
public interface DishMapper {

    /** 包括上架、下架菜品；只要仍引用该分类，就不能单独删除分类。 */
    @Select("select count(*) from t_dish where category_id = #{categoryId}")
    long countByCategoryId(Long categoryId);

    /** 先限定当前门店，再按名称、上下架状态和分类筛选；分页交给 PageHelper。 */
    @Select("select * from t_dish " +
            "where store_id = #{storeId} " +
            "and (#{name} is null or name like concat('%', #{name}, '%')) " +
            "and (#{status} is null or status = #{status}) " +
            "and (#{categoryId} is null or category_id = #{categoryId}) " +
            "order by sort asc, id asc")
    List<Dish> list(@Param("name") String name,
                    @Param("status") Integer status,
                    @Param("categoryId") Long categoryId,
                    @Param("storeId") Long storeId);

    /** 用户端详情按全局唯一菜品 ID 查询，保留文档规定的上下架状态，不加写锁。 */
    @Select("select * from t_dish where id = #{id}")
    Dish findById(@Param("id") Long id);

    /** 新增菜品，主键由数据库生成；返回影响行数，并把主键回填到 dish.id。 */
    @Insert("insert into t_dish(store_id, category_id, name, price, image, description, status, sort, create_time, update_time) " +
            "values(#{storeId}, #{categoryId}, #{name}, #{price}, #{image}, #{description}, #{status}, #{sort}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int addDish(Dish dish);

    /** 修改本门店指定菜品，未传的可选字段保留原值，创建时间与门店归属不变。 */
    @Update("update t_dish set name = #{name}, category_id = #{categoryId}, price = #{price}, " +
            "image = coalesce(#{image}, image), description = coalesce(#{description}, description), " +
            "status = coalesce(#{status}, status), sort = coalesce(#{sort}, sort), " +
            "update_time = #{updateTime} " +
            "where id = #{id} and store_id = #{storeId}")
    int updateDish(Dish dish);

    /** 限定当前门店后锁定目标菜品，供上下架、删除操作检查归属和当前状态。 */
    @Select("select * from t_dish where id = #{id} and store_id = #{storeId} for update")
    Dish findByIdAndStoreIdForUpdate(@Param("id") Long id, @Param("storeId") Long storeId);

    /** 只修改本店目标菜品的状态、更新时间，返回影响行数。 */
    @Update("update t_dish set status = #{status}, update_time = #{updateTime} " +
            "where id = #{id} and store_id = #{storeId}")
    int updateDishStatus(@Param("id") Long id,
                         @Param("status") Integer status,
                         @Param("storeId") Long storeId,
                         @Param("updateTime") LocalDateTime updateTime);

    /** 只删除当前门店的目标菜品，历史订单快照不参与删除。 */
    @Delete("delete from t_dish where id = #{id} and store_id = #{storeId}")
    int deleteDish(@Param("id") Long id, @Param("storeId") Long storeId);
}
