package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.Store;
import com.wys.orderingserver.service.AppStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


/** 用户端营业门店列表查询。 */
@Service
public class AppStoreServiceimpl implements AppStoreService {
    @Autowired
    private StoreMapper storeMapper;

    @Override
    public List<Store> find() {
        return storeMapper.list(null, 1, null);
    }

}
