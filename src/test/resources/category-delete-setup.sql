CREATE TABLE t_dish (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    category_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1
);
