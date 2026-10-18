package com.wys.orderingserver.controller;

import com.wys.orderingserver.pojo.Order;
import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/order")
public class OrderController {
    @Autowired
    private OrderService orderService;
    @GetMapping("/page")
    public Result<PageBean<Order>>page(@RequestParam("pageNum") Integer pageNum,
                                       @RequestParam("pageSize") Integer pageSize,
                                       @RequestParam(value = "status", required = false) Integer status,
                                       @RequestParam(value = "beginTime", required = false)
                                       @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
                                       @RequestParam(value = "endTime", required = false)
                                       @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        PageBean<Order> pageBean = orderService.page(pageNum, pageSize, status, beginTime, endTime);
        return Result.success(pageBean);

    }
    @PutMapping("/accept/{id}")
    public Result accept(@PathVariable("id") Long id) {
        orderService.accept(id);
        return Result.success();
    }
    @PutMapping("/complete/{id}")
    public Result complete(@PathVariable("id") Long id) {
        orderService.complete(id);
        return Result.success();
    }

    @PutMapping("/cancel/{id}")
    public Result cancel(@PathVariable("id") Long id, @RequestBody Order order) {
        // 请求体只读取拒单原因，门店、目标状态和更新时间由服务端决定。
        orderService.cancel(id, order.getCancelReason());
        return Result.success();
    }
}
