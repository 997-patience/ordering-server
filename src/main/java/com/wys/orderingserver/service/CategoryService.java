package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.Category;

import java.util.List;


public interface CategoryService {
    /** 超管必须指定 storeId；门店管理员使用登录信息中的 storeId，查询本店全部分类。 */
    List<Category> getCategory(Long storeId);
    /** 新增当前登录员工所属门店的分类，不使用请求体指定的门店 ID。 */
    void add(Category category);
    /** 修改当前登录员工所属门店的分类，未提供的排序号、状态保留原值。 */
    void updateCategory(Category category);

    /** 删除本店没有关联菜品的分类。 */
    void deleteCategory(Long id);
}
