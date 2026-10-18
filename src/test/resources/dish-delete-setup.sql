CREATE TABLE t_cart (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    dish_id BIGINT NOT NULL REFERENCES t_dish(id),
    quantity INT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    UNIQUE(user_id, dish_id)
);

CREATE TABLE t_orders (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    order_no VARCHAR(32) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status TINYINT NOT NULL
);

-- dish_id 只用于历史溯源，不对 t_dish 建立外键，快照可独立展示。
CREATE TABLE t_order_detail (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES t_orders(id),
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(100) NOT NULL,
    dish_image VARCHAR(255),
    dish_price DECIMAL(10, 2) NOT NULL,
    quantity INT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    create_time TIMESTAMP NOT NULL
);
