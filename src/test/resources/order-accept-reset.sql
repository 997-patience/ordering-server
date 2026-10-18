-- 仅在 OrderAcceptIntegrationTests 的独立 H2 数据库中还原订单测试状态。
UPDATE t_orders
SET status = CASE id WHEN 102 THEN 2 WHEN 103 THEN 3 WHEN 104 THEN 4 ELSE 1 END,
    update_time = '2000-01-01 00:00:00';
