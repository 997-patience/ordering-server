CREATE TABLE t_cart (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    dish_id BIGINT NOT NULL REFERENCES t_dish(id),
    quantity INT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    UNIQUE(user_id, dish_id)
);

CREATE TABLE t_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    amount DECIMAL(10, 2) NOT NULL,
    status TINYINT NOT NULL,
    pay_status TINYINT NOT NULL,
    pay_time TIMESTAMP,
    remark VARCHAR(255),
    pickup_type TINYINT NOT NULL,
    pickup_time TIMESTAMP,
    cancel_reason VARCHAR(255),
    finish_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);
CREATE INDEX idx_status_create ON t_orders(status, create_time);

-- 快照不对菜品建立外键，菜品日后删除仍能保留历史内容。
CREATE TABLE t_order_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES t_orders(id),
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(100) NOT NULL,
    dish_image VARCHAR(255),
    dish_price DECIMAL(10, 2) NOT NULL,
    quantity INT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    create_time TIMESTAMP NOT NULL
);
