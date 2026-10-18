package com.wys.orderingserver.controller;


import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.pojo.TodayStatsVO;
import com.wys.orderingserver.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/stats/today")
public class StatsController {
    @Autowired
    private StatsService statsService;
    @GetMapping
    public Result<TodayStatsVO> getStats() {
        TodayStatsVO stats = statsService.getStats();
        return Result.success(stats);
    }
}
