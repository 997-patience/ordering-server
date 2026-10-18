package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.TodayStatsVO;

public interface StatsService {
    //查询本门店今日的订单数量与营业额统计
    TodayStatsVO getStats();
}
