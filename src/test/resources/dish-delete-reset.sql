-- 仅在 DishDeleteIntegrationTests 的独立 H2 内存数据库重置关联测试数据。
DELETE FROM t_cart;
DELETE FROM t_order_detail;
DELETE FROM t_orders;
