package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.pojo.Store;

/** 门店业务接口。 */
public interface StoreService {

    /** 根据当前员工权限，分页查询符合名称、营业状态条件的门店。 */
    PageBean<Store> page(Integer pageNum, Integer pageSize, String name, Integer status);
    //增加门店
    void add(Store store);
    //删除门店
    void delete(Long id);
    //修改门店信息
    void updateStore(Store store);
}
