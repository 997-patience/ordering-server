-- 此文件只由 StoreDeleteIntegrationTests 对独立 H2 内存数据库执行。
DELETE FROM t_order_detail;
DELETE FROM t_cart;
DELETE FROM t_orders;
DELETE FROM t_dish;
DELETE FROM t_category;
DELETE FROM t_employee;
DELETE FROM t_store;
DELETE FROM t_address;
DELETE FROM t_user;

INSERT INTO t_store(id, name) VALUES (1, '待删除门店'), (2, '保留门店'), (3, '没有商品的门店');
INSERT INTO t_user VALUES (1, '13800000001');
INSERT INTO t_address VALUES (1, 1);
INSERT INTO t_employee(id, username, store_id, role, status) VALUES
    (1, 'admin', NULL, 1, 1),
    (2, 'store1', 1, 2, 1),
    (3, 'store2', 2, 2, 1),
    (4, 'store3', 3, 2, 1);
INSERT INTO t_category VALUES (11, 1, '本店分类'), (21, 2, '其他店分类');
INSERT INTO t_dish VALUES (111, 1, 11, '本店菜品'), (211, 2, 21, '其他店菜品');
INSERT INTO t_orders VALUES (1001, 1, 1, 'ORDER-1'), (2001, 2, 1, 'ORDER-2');
INSERT INTO t_order_detail VALUES (11001, 1001, 111), (21001, 2001, 211);
INSERT INTO t_cart VALUES (1, 1, 1, 111), (2, 2, 1, 211);
