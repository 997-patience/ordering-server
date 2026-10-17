CREATE TABLE t_category (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    name VARCHAR(50) NOT NULL,
    sort INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 插入顺序和主键顺序均不等于期望的 sort、id 排序，验证 SQL 确实排序。
INSERT INTO t_category(id, store_id, name, sort, status, create_time, update_time) VALUES
    (1, 1, '主食', 2, 1, '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (3, 1, '热菜', 1, 1, '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (2, 1, '饮品', 1, 0, '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (4, 2, '其他门店分类', 0, 1, '2026-10-11 09:10:05', '2026-10-11 09:10:05');
