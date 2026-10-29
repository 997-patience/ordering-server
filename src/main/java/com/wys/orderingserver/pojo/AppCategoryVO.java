package com.wys.orderingserver.pojo;

import lombok.Data;

/** 8.2 用户端分类列表中的一项，仅返回接口文档规定的字段。 */
@Data
public class AppCategoryVO {

    private Long id;
    private String name;
    private Integer sort;
}
