-- 仅调整 StoreUpdateIntegrationTests 的独立 H2 表，使字段上限与 ordering-ddl.sql 一致。
ALTER TABLE t_store ALTER COLUMN name VARCHAR(100) NOT NULL;
ALTER TABLE t_store ALTER COLUMN notice VARCHAR(500);

INSERT INTO t_employee VALUES
    (3, 'bound-admin', 1, 1, 1),
    (4, 'unbound-staff', NULL, 2, 1),
    (5, 'store2', 2, 2, 1);

INSERT INTO t_store(id, name, address, phone, open_time, close_time, notice, status, create_time, update_time)
SELECT 3000000000, '大ID门店', address, phone, open_time, close_time, notice, status, create_time, update_time
FROM t_store WHERE id = 1;
