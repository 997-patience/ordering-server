package com.wys.orderingserver.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 今日数据看板返回对象，对应 GET /admin/stats/today 的 data。
 * 统计当前登录门店的数据，由 StatsService 汇总查询结果后填充。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodayStatsVO {

    /** 今日下单的订单总数，按 create_time 统计，默认 0。 */
    private Long orderCount = 0L;

    /** 今日下单且当前为待接单（状态 1）的数量，默认 0。 */
    private Long pendingCount = 0L;

    /** 今日下单且当前为已完成（状态 3）的数量，默认 0。 */
    private Long finishedCount = 0L;

    /** 今日下单且当前为已取消（状态 4）的数量，默认 0。 */
    private Long canceledCount = 0L;

    /** 今日营业额（元）：仅汇总状态为 3 且 finish_time 在今日的订单金额。 */
    private BigDecimal turnover = new BigDecimal("0.00");
}
