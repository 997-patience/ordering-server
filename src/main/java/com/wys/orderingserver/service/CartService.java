package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.Cart;
import com.wys.orderingserver.pojo.CartItemVO;

import java.util.List;

/** 用户端购物车业务，对应文档 9.1～9.4。 */
public interface CartService {
    //查询当前登录用户的购物车列表
    List<CartItemVO> find(Long storeId);
    //把菜品加入购物车，重复加购时**累加数量**而不是新增一行
    void add(Cart cart);
    //按当前用户和菜品定位已有条目，将数量设置为请求的目标值
    void update(Cart cart);

    /** 删除当前用户指定主键的购物车项，已不存在时保持成功。 */
    void delete(Long id);
}
