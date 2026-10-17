-- 只对 CategoryDeleteIntegrationTests 的独立 H2 数据库执行。
DELETE FROM t_dish;
DELETE FROM t_category;
INSERT INTO t_category(id, store_id, name, sort, status) VALUES
    (101, 1, '可删除的空分类', 1, 1),
    (102, 1, '有上架菜品的分类', 2, 1),
    (103, 1, '有下架菜品的分类', 3, 0),
    (201, 2, '其他店空分类', 1, 1),
    (202, 2, '其他店有菜品的分类', 2, 1);
INSERT INTO t_dish VALUES
    (1001, 1, 102, '上架菜品', 1),
    (1002, 1, 103, '下架菜品', 0),
    (2001, 2, 202, '其他门店菜品', 1);
