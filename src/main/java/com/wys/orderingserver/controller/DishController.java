package com.wys.orderingserver.controller;

import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/dish")
public class DishController {
    @Autowired
    private DishService dishService;
    @GetMapping("/page")
    public Result<PageBean<Dish>>page(@RequestParam("pageNum") Integer pageNum,
                                      @RequestParam("pageSize") Integer pageSize,
                                      @RequestParam(value = "name", required = false) String name,
                                      @RequestParam(value = "status", required = false) Integer status,
                                      @RequestParam(value = "categoryId" , required = false) Long categoryId) {
        PageBean<Dish> pageBean = dishService.page(pageNum, pageSize, name, status, categoryId);
        return Result.success(pageBean);

    }
    @PostMapping
    public Result addDish(@Validated(Dish.Add.class) @RequestBody Dish dish) {
        dishService.addDish(dish);
        return Result.success();
    }
    @PutMapping
    public Result updateDish(@Validated(Dish.Update.class) @RequestBody Dish dish) {
        dishService.updateDish(dish);
        return Result.success();
    }
    @PutMapping("/status/{id}")
    public Result updateDishStatus(@PathVariable("id") Long id, @RequestParam("status") Integer status) {
        dishService.updateDishStatus(id, status);
        return Result.success();
    }
    @DeleteMapping
    public Result deleteDish(@RequestParam("id") Long id) {
        dishService.deleteDish(id);
        return Result.success();
    }

}
