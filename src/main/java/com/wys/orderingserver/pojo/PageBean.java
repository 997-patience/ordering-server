package com.wys.orderingserver.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页返回结果对象
 * 配合 PageHelper 使用（见 ArticleServiceimpl 同款写法）：
 * PageHelper.startPage(pageNum, pageSize);
 * List<T> list = mapper.list(...);
 * Page<T> p = (Page<T>) list;
 * pb.setTotal(p.getTotal());
 * pb.setItems(p.getResult());
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageBean<T> {

    /** 总条数 */
    private Long total;

    /** 当前页数据集合 */
    private List<T> items;
}
