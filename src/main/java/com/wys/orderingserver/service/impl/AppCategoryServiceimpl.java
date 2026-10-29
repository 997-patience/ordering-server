package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CategoryMapper;
import com.wys.orderingserver.pojo.AppCategoryVO;
import com.wys.orderingserver.service.AppCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** 用户端分类浏览，不使用管理员绑定门店的权限规则。 */
@Service
public class AppCategoryServiceimpl implements AppCategoryService {

    @Autowired
    private CategoryMapper categoryMapper;

    @Override
    public List<AppCategoryVO> list(Long storeId) {
        if (storeId == null || storeId < 1) {
            throw new BizException("请选择有效的门店ID");
        }
        // storeId 来自顾客选择的门店；SQL 同时限制门店和启用状态。
        return categoryMapper.listEnabledByStoreId(storeId);
    }
}
