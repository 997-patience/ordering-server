package com.wys.orderingserver.controller.admin;

import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.pojo.Store;
import com.wys.orderingserver.service.StoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 门店管理接口。 */
@RestController
@RequestMapping("/admin/store")
public class StoreController {
    @Autowired
    private  StoreService storeService;

    // Spring 会通过构造方法注入 StoreService 的实现对象。
//    public StoreController(StoreService storeService) {
//        this.storeService = storeService;
//    }

    /** 2.1 门店分页查询：参数来自 URL 查询字符串，不是 JSON 请求体。 */
    @GetMapping("/page")
    public Result<PageBean<Store>> page(
            @RequestParam("pageNum") Integer pageNum,
            @RequestParam("pageSize") Integer pageSize,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "status", required = false) Integer status) {
        PageBean<Store> pageBean = storeService.page(pageNum, pageSize, name, status);
        return Result.success(pageBean);
    }
    @PostMapping
    public Result addStore(@Validated(Store.Add.class) @RequestBody Store store) {
        storeService.add(store);
        return Result.success();
    }
    /** 2.3 修改门店：ID、名称必填，选填字段省略时保留原值。 */
    @PutMapping
    public Result updateStore(@Validated(Store.Update.class) @RequestBody Store store) {
        storeService.updateStore(store);
        return Result.success();
    }

    @DeleteMapping
    public Result delete(@RequestParam("id") Long id){
        storeService.delete(id);
        return Result.success();
    }
}
