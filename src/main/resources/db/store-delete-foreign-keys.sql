-- 在现有 ordering 数据库应用一次；本文件不删除任何业务记录，也不会被应用自动执行。
-- 应用前先修复指向不存在门店/订单的记录，否则数据库会拒绝添加约束。
-- RESTRICT 配合 Service 中显式的子表清理，防止并发请求写入孤立数据。

ALTER TABLE t_employee ADD CONSTRAINT fk_employee_store
    FOREIGN KEY (store_id) REFERENCES t_store(id) ON DELETE RESTRICT;

ALTER TABLE t_category ADD CONSTRAINT fk_category_store
    FOREIGN KEY (store_id) REFERENCES t_store(id) ON DELETE RESTRICT;

ALTER TABLE t_dish ADD CONSTRAINT fk_dish_store
    FOREIGN KEY (store_id) REFERENCES t_store(id) ON DELETE RESTRICT;

ALTER TABLE t_cart ADD CONSTRAINT fk_cart_store
    FOREIGN KEY (store_id) REFERENCES t_store(id) ON DELETE RESTRICT;

ALTER TABLE t_orders ADD CONSTRAINT fk_orders_store
    FOREIGN KEY (store_id) REFERENCES t_store(id) ON DELETE RESTRICT;

ALTER TABLE t_order_detail ADD CONSTRAINT fk_detail_order
    FOREIGN KEY (order_id) REFERENCES t_orders(id) ON DELETE RESTRICT;
