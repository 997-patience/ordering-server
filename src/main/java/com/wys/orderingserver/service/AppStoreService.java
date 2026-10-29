package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.Store;

import java.util.List;

/** 用户端门店浏览业务，对应文档 8.1。 */
public interface AppStoreService {
    //查询所有营业中的门店
    List<Store> find();
}
