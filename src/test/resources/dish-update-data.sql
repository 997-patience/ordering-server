-- 仅对 DishUpdateIntegrationTests 的独立 H2 内存数据库执行。
DELETE FROM t_dish;
INSERT INTO t_dish(id, store_id, category_id, name, price, image, description, status, sort, create_time, update_time) VALUES
    (101, 1, 11, '宫保鸡丁', 38.00, '/upload/old.jpg', '原描述', 0, 7, '2000-01-01 00:00:00', '2000-01-01 00:00:00'),
    (102, 1, 11, '土豆丝', 16.00, NULL, NULL, 1, 2, '2001-01-01 00:00:00', '2001-01-01 00:00:00'),
    (201, 2, 21, '其他店菜品', 50.00, '/upload/other.jpg', '其他店描述', 1, 1, '2002-01-01 00:00:00', '2002-01-01 00:00:00');
