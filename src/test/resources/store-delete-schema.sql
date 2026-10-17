CREATE TABLE t_store (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    address VARCHAR(255),
    phone VARCHAR(20),
    open_time TIME,
    close_time TIME,
    notice VARCHAR(500),
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE t_user (id BIGINT PRIMARY KEY, phone VARCHAR(20));
CREATE TABLE t_address (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES t_user(id)
);

CREATE TABLE t_employee (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50),
    password VARCHAR(100),
    name VARCHAR(50),
    phone VARCHAR(20),
    store_id BIGINT,
    role TINYINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 1
);

CREATE TABLE t_category (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name VARCHAR(50)
);

CREATE TABLE t_dish (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL REFERENCES t_category(id),
    name VARCHAR(100)
);

CREATE TABLE t_orders (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES t_user(id),
    order_no VARCHAR(32)
);

CREATE TABLE t_order_detail (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    dish_id BIGINT NOT NULL REFERENCES t_dish(id)
);

CREATE TABLE t_cart (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES t_user(id),
    dish_id BIGINT NOT NULL REFERENCES t_dish(id)
);
