package com.wys.orderingserver.controller.app;

import com.wys.orderingserver.pojo.Cart;
import com.wys.orderingserver.pojo.CartItemVO;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.service.CartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 用户端购物车入口，对应文档 9.1～9.4。 */
@RestController
@RequestMapping("/app/cart")
public class AppCartController {
    @Autowired
    private CartService cartService;
    @GetMapping("/list")
    public Result<List<CartItemVO>> find(@RequestParam(value = "storeId",required = false) Long storeId) {
        List<CartItemVO> carts=cartService.find(storeId);
        return Result.success(carts);
    }
    @PostMapping
    public Result add(
            @Validated(Cart.Add.class) @RequestBody Cart cart) {

        cartService.add(cart);
        return Result.success();
    }
    @PutMapping
    public Result update(@Validated(Cart.Update.class) @RequestBody Cart cart) {
        cartService.update(cart);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") Long id) {
        cartService.delete(id);
        return Result.success();
    }
}
