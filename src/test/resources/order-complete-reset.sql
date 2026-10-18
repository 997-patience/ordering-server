-- 仅用于 OrderCompleteIntegrationTests 的独立 H2 数据库。
UPDATE t_orders
SET status = CASE id WHEN 101 THEN 1 WHEN 103 THEN 3 WHEN 104 THEN 4 WHEN 106 THEN 1 ELSE 2 END,
    finish_time = CASE WHEN id = 103 THEN TIMESTAMP '2026-10-12 13:00:00' ELSE NULL END,
    update_time = '2000-01-01 00:00:00';
