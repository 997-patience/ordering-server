-- 仅用于 AppCartListIntegrationTests 的独立 H2；同一门店、同一道菜属于不同用户。
INSERT INTO t_user(id, phone, password, nickname) VALUES
    (2, '13900000002', 'e10adc3949ba59abbe56e057f20f883e', '小红'),
    (3, '13700000003', 'e10adc3949ba59abbe56e057f20f883e', '空购物车用户');
INSERT INTO t_cart(id, user_id, store_id, dish_id, quantity, create_time, update_time) VALUES
    (1004, 1, 1, 102, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1002, 2, 1, 101, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1003, 1, 2, 201, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1001, 1, 1, 101, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
