package com.wys.orderingserver.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.Order;
import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.service.OrderService;
import com.wys.orderingserver.utils.AdminContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class OrderServiceimpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StoreMapper storeMapper;
    @Override
    public PageBean<Order> page(Integer pageNum, Integer pageSize, Integer status, LocalDateTime beginTime, LocalDateTime endTime) {
        if (pageNum == null || pageNum < 1) {
            throw new BizException("pageNum必须大于等于1");
        }
        if (pageSize == null || pageSize < 1) {
            throw new BizException("pageSize必须大于等于1");
        }
        if (status != null && (status < 1 || status > 4)) {
            throw new BizException("订单状态只能为1、2、3或4");
        }
        if (beginTime != null && endTime != null && beginTime.isAfter(endTime)) {
            throw new BizException("开始时间不能晚于结束时间");
        }
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        // 所有校验在开启分页前完成；时间区间和门店范围都由 SQL 过滤，再统计总数。
        try (Page<Order> page = PageHelper.startPage(pageNum, pageSize)) {
            orderMapper.list(storeId, status, beginTime, endTime);
            return new PageBean<>(page.getTotal(), page.getResult());
        }
    }

    @Override
    @Transactional
    public void accept(Long id) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        if (id == null || id < 1) {
            throw new BizException("请选择有效的订单ID");
        }
        // 与整店删除保持相同顺序：先锁门店，再按门店范围锁定目标订单。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        Order order = orderMapper.findByIdAndStoreIdForUpdate(id, storeId);
        if (order == null) {
            throw new BizException("订单不存在或不属于当前门店");
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            throw new BizException("当前订单状态不允许接单");
        }
        // 先执行更新，再检查影响行数；SQL 同时限制门店和原状态，避免覆盖其他状态。
        LocalDateTime updateTime = LocalDateTime.now();
        int rows = orderMapper.accept(id, storeId, updateTime);
        if (rows != 1) {
            throw new BizException("当前订单状态不允许接单");
        }
    }

    @Override
    @Transactional
    public void complete(Long id) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        if (id == null || id < 1) {
            throw new BizException("请选择有效的订单ID");
        }
        // 与整店删除保持相同顺序：先锁门店，再按门店范围锁定目标订单。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        Order order = orderMapper.findByIdAndStoreIdForUpdate(id, storeId);
        if (order == null) {
            throw new BizException("订单不存在或不属于当前门店");
        }
        if (!Integer.valueOf(2).equals(order.getStatus())) {
            throw new BizException("当前订单状态不能完成");
        }
        // 一次更新状态、更新时间和完成时间，避免分开写入造成不一致。
        LocalDateTime completeTime = LocalDateTime.now();
        int rows = orderMapper.complete(id, storeId, completeTime);
        if (rows != 1) {
            throw new BizException("当前订单状态不能完成");
        }
    }

    @Override
    @Transactional
    public void cancel(Long id, String cancelReason) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        if (id == null || id < 1) {
            throw new BizException("请选择有效的订单ID");
        }
        if (!StringUtils.hasText(cancelReason)) {
            throw new BizException("拒单原因不能为空");
        }
        String reason = cancelReason.strip();
        if (reason.length() > 255) {
            throw new BizException("拒单原因不能超过255个字符");
        }

        // 与接单、完成订单、整店删除保持相同顺序：先锁门店，再锁订单。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        Order order = orderMapper.findByIdAndStoreIdForUpdate(id, storeId);
        if (order == null) {
            throw new BizException("订单不存在或不属于当前门店");
        }
        // 文档通用状态表仅允许 1 -> 4；已接单、已完成和已取消都不能拒单。
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            throw new BizException("当前订单状态不允许拒单");
        }
        LocalDateTime updateTime = LocalDateTime.now();
        int rows = orderMapper.cancel(id, storeId, reason, updateTime);
        if (rows != 1) {
            throw new BizException("当前订单状态不允许拒单");
        }
    }
}
