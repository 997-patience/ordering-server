package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.Order;
import com.wys.orderingserver.pojo.PageBean;

import java.time.LocalDateTime;

public interface OrderService {
    //分页查询本门店的订单，支持按状态与下单时间区间筛选
    PageBean<Order> page(Integer pageNum, Integer pageSize, Integer status, LocalDateTime beginTime, LocalDateTime endTime);
    //商家接单，订单状态由 1 待接单 变更为 2 已接单
    void accept(Long id);
    //商家完成订单，订单状态由 2 已接单 变更为 3 已完成，并记录完成时间
    void complete(Long id);
    //商家拒单，只允许 1 待接单 变更为 4 已取消，并记录拒单原因
    void cancel(Long id, String cancelReason);
}
