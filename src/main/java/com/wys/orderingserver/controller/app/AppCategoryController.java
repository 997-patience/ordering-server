package com.wys.orderingserver.controller.app;

import com.wys.orderingserver.pojo.AppCategoryVO;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.service.AppCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户端分类列表入口，对应文档 8.2。 */
@RestController
@RequestMapping("/app/category")
public class AppCategoryController {

    @Autowired
    private AppCategoryService appCategoryService;

    @GetMapping("/list")
    public Result<List<AppCategoryVO>> list(@RequestParam("storeId") Long storeId) {
        return Result.success(appCategoryService.list(storeId));
    }
}
