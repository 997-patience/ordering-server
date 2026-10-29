package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.pojo.TodayStatsVO;
import com.wys.orderingserver.service.StatsService;
import com.wys.orderingserver.utils.AdminContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class StatsServiceimpl implements StatsService {
    @Autowired
    private OrderMapper orderMapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TodayStatsVO getStats() {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        // 按上海业务日期计算一次边界，五次查询共用 [今日零点, 明日零点)。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime beginTime = today.atStartOfDay();
        LocalDateTime endTime = today.plusDays(1).atStartOfDay();

        TodayStatsVO stats = new TodayStatsVO();
        // 数量按今日下单的订单统计，status 为 null 时统计全部状态。
        stats.setOrderCount(orderMapper.countByCreateTime(storeId, null, beginTime, endTime));
        stats.setPendingCount(orderMapper.countByCreateTime(storeId, 1, beginTime, endTime));
        stats.setFinishedCount(orderMapper.countByCreateTime(storeId, 3, beginTime, endTime));
        stats.setCanceledCount(orderMapper.countByCreateTime(storeId, 4, beginTime, endTime));
        // 营业额只看已完成订单的完成时间，不能直接复用上面的下单时间筛选。
        stats.setTurnover(orderMapper.sumTurnover(storeId, beginTime, endTime).setScale(2));
        return stats;
    }
}
