package com.wys.orderingserver.mapper;

import com.wys.orderingserver.pojo.Category;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CategoryMapper {

    /** 管理端查询全部分类（含禁用分类），按排序号、主键稳定排序。 */
    @Select("select * from t_category where store_id = #{storeId} order by sort asc, id asc")
    List<Category> getCategory(Long storeId);

    /** 新增分类，主键由数据库自增生成。 */
    @Insert("insert into t_category(store_id, name, sort, status, create_time, update_time) " +
            "values(#{storeId}, #{name}, #{sort}, #{status}, #{createTime}, #{updateTime})")
    void add(Category category);
    /** 修改分类；门店条件限制操作范围，返回实际匹配的记录数供 Service 检查。 */
    @Update("update t_category set name = #{name}, " +
            "sort = coalesce(#{sort}, sort), " +
            "status = coalesce(#{status}, status), " +
            "update_time = #{updateTime} " +
            "where id = #{id} and store_id = #{storeId}")
    int updateCategory(Category category);

    /** 先限制门店归属，再锁定待删除的分类。 */
    @Select("select * from t_category where id = #{id} and store_id = #{storeId} for update")
    Category findByIdAndStoreIdForUpdate(@Param("id") Long id, @Param("storeId") Long storeId);

    /** 同时限定分类 ID 和所属门店，防止跨店删除。 */
    @Delete("delete from t_category where id = #{id} and store_id = #{storeId}")
    int deleteCategory(@Param("id") Long id, @Param("storeId") Long storeId);
}
