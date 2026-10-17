package com.wys.orderingserver.controller;

import com.wys.orderingserver.pojo.Category;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/category")
public class CategoryController {
    @Autowired
    private CategoryService categoryService;

    /** 3.1 分类列表：超管指定门店，门店管理员自动查询本店。 */
    @GetMapping("/list")
    public Result<List<Category>> getCategory(
            @RequestParam(value = "storeId", required = false) Long storeId) {
        List<Category> categories = categoryService.getCategory(storeId);
        return Result.success(categories);
    }
    /** 3.2 新增分类：接收 JSON，门店归属由服务端从登录信息填充。 */
    @PostMapping
    public Result addCategory(@Validated(Category.Add.class) @RequestBody Category category) {
        categoryService.add(category);
        return Result.success();
    }
    /** 3.3 修改分类：校验必填的 ID、名称，可选字段未传时保留原值。 */
    @PutMapping
    public Result updateCategory(@Validated(Category.Update.class) @RequestBody Category category) {
        categoryService.updateCategory(category);
        return Result.success();
    }

    /** 3.4 删除分类：ID 来自查询字符串，所属门店由服务端确定。 */
    @DeleteMapping
    public Result deleteCategory(@RequestParam("id") Long id) {
        categoryService.deleteCategory(id);
        return Result.success();
    }
}
