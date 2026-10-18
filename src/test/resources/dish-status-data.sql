-- 在独立 H2 测试数据库中增加一个超过 Integer 范围的菜品 ID。
INSERT INTO t_dish(id, store_id, category_id, name, price, image, description, status, sort, create_time, update_time)
SELECT 3000000000, store_id, category_id, '大ID菜品', price, image, description, status, sort, create_time, update_time
FROM t_dish WHERE id = 101;
