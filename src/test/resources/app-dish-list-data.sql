DELETE FROM t_order_detail;
DELETE FROM t_orders;
DELETE FROM t_cart;
DELETE FROM t_dish;
DELETE FROM t_category;
DELETE FROM t_employee;
DELETE FROM t_store;
DELETE FROM t_user;

INSERT INTO t_store(id, name, status) VALUES (1, '一号店', 1), (2, '二号店', 1), (3, '空门店', 0);
INSERT INTO t_employee(id, username, store_id, role, status) VALUES
    (1, 'admin', NULL, 1, 1), (2, 'store1', 1, 2, 1);
INSERT INTO t_user(id, phone, password, nickname) VALUES
    (1, '13800000001', 'e10adc3949ba59abbe56e057f20f883e', '小明');
INSERT INTO t_category(id, store_id, name, sort, status) VALUES
    (11, 1, '热菜', 1, 1), (12, 1, '分类二', 2, 1),
    (21, 2, '其他店分类', 1, 1), (3000000000, 1, '大ID分类', 3, 1);
INSERT INTO t_dish(id, store_id, category_id, name, price, image, description, status, sort, create_time, update_time) VALUES
    (101, 1, 11, '宫保鸡丁', 38.00, '/upload/chicken.jpg', '微辣', 1, 2, '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (102, 1, 11, '下架菜品', 20.00, NULL, NULL, 0, 1, '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (103, 1, 12, '土豆丝', 16.00, NULL, NULL, 1, 1, '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (104, 1, 3000000000, '米饭', 2.00, NULL, NULL, 1, 3, '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (201, 2, 21, '其他店菜品', 45.00, '/upload/other.jpg', '其他门店', 1, 0, '2026-10-11 09:10:05', '2026-10-11 09:10:05');
