CREATE TABLE t_dish (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    category_id BIGINT NOT NULL REFERENCES t_category(id),
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    image VARCHAR(255),
    description VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 1,
    sort INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

INSERT INTO t_category(id, store_id, name, sort, status) VALUES
    (11, 1, '热菜', 0, 1),
    (12, 1, '停用分类', 1, 0),
    (21, 2, '其他门店分类', 0, 1),
    (3000000000, 1, '大ID分类', 2, 1);
