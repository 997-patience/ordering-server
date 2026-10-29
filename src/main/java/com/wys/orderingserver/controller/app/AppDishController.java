package com.wys.orderingserver.controller.app;

import com.wys.orderingserver.pojo.AppDishVO;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.service.AppDishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户端菜品查询入口，对应文档 8.3 菜品列表、8.4 菜品详情。 */
@RestController
@RequestMapping("/app/dish")
public class AppDishController {
    @Autowired
    private AppDishService appDishService;
    
    @GetMapping("/list")
    public Result<List<AppDishVO>> find(@RequestParam("storeId") Long storeId,
                                   @RequestParam(value="categoryId",required = false) Long categoryId){
        List<AppDishVO> dishes=appDishService.find(storeId,categoryId);
        return Result.success(dishes);
    }
    @GetMapping("/{id}")
    public Result<AppDishVO> getDetail(@PathVariable("id") Long id) {
        AppDishVO dish = appDishService.getDetail(id);
        return Result.success(dish);
    }
}
