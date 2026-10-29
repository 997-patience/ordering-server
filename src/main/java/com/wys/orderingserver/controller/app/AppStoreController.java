package com.wys.orderingserver.controller.app;

import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.pojo.Store;
import com.wys.orderingserver.service.AppStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户端门店查询入口，对应文档 8.1。 */
@RestController
@RequestMapping("/app/store")
public class AppStoreController {
    @Autowired
    private AppStoreService appStoreService;
    @GetMapping("/list")
    public Result<List<Store>> find(){
        List<Store> stores =   appStoreService.find();
        return Result.success(stores);
    }

}
