package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.AppOrderListVO;
import com.wys.orderingserver.pojo.AppOrderDetailVO;
import com.wys.orderingserver.pojo.OrderSubmitDTO;
import com.wys.orderingserver.pojo.OrderSubmitVO;
import com.wys.orderingserver.pojo.PageBean;

/** 用户端订单业务，对应文档 10.x；与现有管理端 OrderService 分开。 */
public interface AppOrderService {
    //提交订单，服务端把购物车中的菜品生成订单主表与订单明细（保存菜品快照），并清空对应购物车
    OrderSubmitVO submit(OrderSubmitDTO request);
    //分页查询当前登录用户的订单，按下单时间倒序
    PageBean<AppOrderListVO> page(Integer pageNum, Integer pageSize, Integer status);
    //查询当前用户某一笔订单的详情，包含菜品明细
    AppOrderDetailVO getById(Long id);
    //用户取消自己的订单
    void cancel(Long id);
}
