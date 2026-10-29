package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.AppCategoryVO;

import java.util.List;

/** 用户端分类浏览业务，对应文档 8.2。 */
public interface AppCategoryService {

    /** 查询用户选择的门店下启用中的分类，无匹配分类时返回空列表。 */
    List<AppCategoryVO> list(Long storeId);
}
