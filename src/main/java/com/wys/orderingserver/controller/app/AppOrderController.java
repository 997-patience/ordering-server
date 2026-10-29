package com.wys.orderingserver.controller.app;

import com.wys.orderingserver.pojo.*;
import com.wys.orderingserver.service.AppOrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 用户端订单入口；提交、本人列表、详情与取消。 */
@RestController
@RequestMapping("/app/order")
public class AppOrderController {
    @Autowired
    private AppOrderService appOrderService;
    @PostMapping("/submit")
    public Result<OrderSubmitVO> submit(@Valid @RequestBody OrderSubmitDTO request) {
        return Result.success(appOrderService.submit(request));
    }
    @GetMapping("/list")
    public Result<PageBean<AppOrderListVO>> page(@RequestParam("pageNum") Integer pageNum,
                                               @RequestParam("pageSize") Integer pageSize,
                                               @RequestParam(value = "status", required = false) Integer status) {
        PageBean<AppOrderListVO> pageBean = appOrderService.page(pageNum, pageSize, status);
        return Result.success(pageBean);
    }
    @GetMapping("/{id}")
    public Result<AppOrderDetailVO> getById(@PathVariable("id") Long id) {
        return Result.success(appOrderService.getById(id));
    }
    @PutMapping("/cancel/{id}")
    public Result cancel(@PathVariable("id") Long id) {
        appOrderService.cancel(id);
        return Result.success();
    }
}
