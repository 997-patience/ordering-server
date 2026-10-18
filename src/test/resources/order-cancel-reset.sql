-- 仅在 OrderCancelIntegrationTests 的独立 H2 数据库中还原数据。
UPDATE t_orders
SET status = CASE id WHEN 102 THEN 2 WHEN 103 THEN 3 WHEN 104 THEN 4 ELSE 1 END,
    cancel_reason = CASE WHEN id = 104 THEN '用户取消' ELSE NULL END,
    finish_time = CASE WHEN id = 103 THEN TIMESTAMP '2026-10-12 13:00:00' ELSE NULL END,
    update_time = '2000-01-01 00:00:00';
