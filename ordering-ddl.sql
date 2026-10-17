-- =====================================================================
--  多门店点餐系统 · 建库建表脚本（主键自增版）
--  项目：ordering-server        数据库：ordering
--  字符集：utf8mb4              引擎：InnoDB
--
--  ── 与项目技术栈的对应关系 ─────────────────────────────────────────
--   持久层：原生 MyBatis（@Mapper + 注解 / XML），不是 MyBatis-Plus
--   分页：  PageHelper（Service 里 PageHelper.startPage）
--   主键：  ★ BIGINT + AUTO_INCREMENT，由数据库生成
--           （实体类对应 Long；不要用 Integer，也不要手填 id）
--
--  ── DataGrip 执行方式 ──────────────────────────────────────────────
--   打开本文件 → 工具栏 ▶ 下拉 → 选 "Run ordering-ddl.sql"（整脚本执行）
--   ⚠️ 不要用右键 "Run Query"，那只会执行光标所在的单条语句
--   执行后在 Database 面板按 Ctrl+F5 刷新才能看到 ordering 库
--
--  ── 9 张表 ────────────────────────────────────────────────────────
--   ① t_user  ② t_employee  ③ t_store  ④ t_category  ⑤ t_dish
--   ⑥ t_cart  ⑦ t_orders    ⑧ t_order_detail      ⑨ t_address（可选）
--
--  ⚠️ 脚本开头的 DROP DATABASE 会清空同名库，开发阶段可反复执行；
--     有不想丢的数据时把开头 3 行注释掉。
-- =====================================================================

DROP DATABASE IF EXISTS `ordering`;
CREATE DATABASE `ordering`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;
USE `ordering`;

SET FOREIGN_KEY_CHECKS = 0;


-- =====================================================================
-- 1. 用户表（点餐的顾客）
-- =====================================================================
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `phone`       VARCHAR(20)  NOT NULL COMMENT '手机号（登录账号）',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（MD5 加密后存储）',
    `nickname`    VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    `avatar`      VARCHAR(255)          DEFAULT NULL COMMENT '头像地址',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`) COMMENT '手机号唯一，注册时防重复'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表（顾客）';


-- =====================================================================
-- 2. 员工表（管理员，登录后台）
-- =====================================================================
DROP TABLE IF EXISTS `t_employee`;
CREATE TABLE `t_employee`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（MD5 加密后存储）',
    `name`        VARCHAR(50)           DEFAULT NULL COMMENT '姓名',
    `phone`       VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    `store_id`    BIGINT                DEFAULT NULL COMMENT '所属门店ID（role=1 超管可为空）',
    `role`        TINYINT      NOT NULL DEFAULT 2 COMMENT '角色：1超管 2门店管理员',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`) COMMENT '用户名唯一',
    KEY `idx_store` (`store_id`) COMMENT '按门店查员工'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='员工表（后台管理员）';


-- =====================================================================
-- 3. 门店表  ★ 全项目的数据隔离维度
-- =====================================================================
DROP TABLE IF EXISTS `t_store`;
CREATE TABLE `t_store`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(100) NOT NULL COMMENT '门店名称',
    `address`     VARCHAR(255)          DEFAULT NULL COMMENT '门店地址',
    `phone`       VARCHAR(20)           DEFAULT NULL COMMENT '门店电话',
    `open_time`   TIME                  DEFAULT NULL COMMENT '营业开始时间',
    `close_time`  TIME                  DEFAULT NULL COMMENT '营业结束时间',
    `notice`      VARCHAR(500)          DEFAULT NULL COMMENT '门店公告',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1营业中 0已打烊',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`) COMMENT '用户端只查营业中的门店'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='门店表';


-- =====================================================================
-- 4. 菜品分类表
-- =====================================================================
DROP TABLE IF EXISTS `t_category`;
CREATE TABLE `t_category`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `store_id`    BIGINT      NOT NULL COMMENT '所属门店ID（数据隔离字段）',
    `name`        VARCHAR(50) NOT NULL COMMENT '分类名称',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_store_sort` (`store_id`, `sort`) COMMENT '按门店查分类并排序'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品分类表';


-- =====================================================================
-- 5. 菜品表
-- =====================================================================
DROP TABLE IF EXISTS `t_dish`;
CREATE TABLE `t_dish`
(
    `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `store_id`    BIGINT         NOT NULL COMMENT '所属门店ID（数据隔离字段）',
    `category_id` BIGINT         NOT NULL COMMENT '所属分类ID',
    `name`        VARCHAR(100)   NOT NULL COMMENT '菜品名称',
    `price`       DECIMAL(10, 2) NOT NULL COMMENT '价格（元）Java 侧用 BigDecimal',
    `image`       VARCHAR(255)            DEFAULT NULL COMMENT '图片地址（相对路径 /upload/xxx.jpg）',
    `description` VARCHAR(500)            DEFAULT NULL COMMENT '菜品描述',
    `status`      TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1上架 0下架',
    `sort`        INT            NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_store_cate_status` (`store_id`, `category_id`, `status`)
        COMMENT '★用户端菜品列表：门店+分类+上架状态（最左前缀）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜品表';


-- =====================================================================
-- 6. 购物车表
--    uk_user_dish 是"重复加购累加数量"的兜底，配合这条 SQL：
--    INSERT INTO t_cart(...) VALUES(...) ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)
-- =====================================================================
DROP TABLE IF EXISTS `t_cart`;
CREATE TABLE `t_cart`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `store_id`    BIGINT   NOT NULL COMMENT '门店ID（冗余，便于按门店清空）',
    `dish_id`     BIGINT   NOT NULL COMMENT '菜品ID',
    `quantity`    INT      NOT NULL DEFAULT 1 COMMENT '数量',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_dish` (`user_id`, `dish_id`) COMMENT '★同一用户同一菜品只允许一行',
    KEY `idx_user_store` (`user_id`, `store_id`) COMMENT '购物车列表查询'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='购物车表';


-- =====================================================================
-- 7. 订单主表
--    状态机：1待接单 → 2已接单 → 3已完成 ／ 4已取消
-- =====================================================================
DROP TABLE IF EXISTS `t_orders`;
CREATE TABLE `t_orders`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`      VARCHAR(32)    NOT NULL COMMENT '订单号（业务唯一）',
    `user_id`       BIGINT         NOT NULL COMMENT '下单用户ID',
    `store_id`      BIGINT         NOT NULL COMMENT '门店ID（商家端只查本店）',
    `amount`        DECIMAL(10, 2) NOT NULL COMMENT '订单总金额（元）',
    `status`        TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1待接单 2已接单 3已完成 4已取消',
    `pay_status`    TINYINT        NOT NULL DEFAULT 0 COMMENT '支付状态：0未支付 1已支付',
    `pay_time`      DATETIME                DEFAULT NULL COMMENT '支付时间',
    `remark`        VARCHAR(255)            DEFAULT NULL COMMENT '订单备注',
    `pickup_type`   TINYINT        NOT NULL DEFAULT 1 COMMENT '取餐方式：1到店自取 2堂食扫码',
    `pickup_time`   DATETIME                DEFAULT NULL COMMENT '预约取餐时间',
    `cancel_reason` VARCHAR(255)            DEFAULT NULL COMMENT '取消/拒单原因',
    `finish_time`   DATETIME                DEFAULT NULL COMMENT '完成时间（今日看板统计用）',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `update_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`) COMMENT '★订单号唯一：防重复提交',
    KEY `idx_user_create` (`user_id`, `create_time`) COMMENT '★我的订单：按用户+时间倒序',
    KEY `idx_store_status_create` (`store_id`, `status`, `create_time`) COMMENT '★商家端订单列表',
    KEY `idx_status_create` (`status`, `create_time`) COMMENT '★超时取消定时任务扫表'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单主表';


-- =====================================================================
-- 8. 订单明细表  ★ 订单快照
--    dish_name / dish_image / dish_price 是下单瞬间从 t_dish 复制的副本，
--    展示订单时只读本表，绝不 JOIN t_dish（菜品改价不影响历史订单）
-- =====================================================================
DROP TABLE IF EXISTS `t_order_detail`;
CREATE TABLE `t_order_detail`
(
    `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`    BIGINT         NOT NULL COMMENT '订单ID',
    `dish_id`     BIGINT         NOT NULL COMMENT '菜品ID（仅用于溯源）',
    `dish_name`   VARCHAR(100)   NOT NULL COMMENT '★快照：下单时菜名',
    `dish_image`  VARCHAR(255)            DEFAULT NULL COMMENT '★快照：下单时图片',
    `dish_price`  DECIMAL(10, 2) NOT NULL COMMENT '★快照：下单时单价',
    `quantity`    INT            NOT NULL COMMENT '购买数量',
    `amount`      DECIMAL(10, 2) NOT NULL COMMENT '小计 = dish_price * quantity',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order` (`order_id`) COMMENT '按订单查明细'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单明细表（含菜品快照）';


-- =====================================================================
-- 9. 收货/联系人表（可选，MVP 用不到可整段注释掉）
-- =====================================================================
DROP TABLE IF EXISTS `t_address`;
CREATE TABLE `t_address`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `contact`     VARCHAR(50)  NOT NULL COMMENT '联系人姓名',
    `phone`       VARCHAR(20)  NOT NULL COMMENT '联系电话',
    `detail`      VARCHAR(255) NOT NULL COMMENT '详细地址',
    `is_default`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认：1是 0否',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`) COMMENT '按用户查联系人'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='收货/联系人表（可选）';

SET FOREIGN_KEY_CHECKS = 1;


-- =====================================================================
-- 初始化测试数据
--   明文密码均为 123456，库里存 MD5：e10adc3949ba59abbe56e057f20f883e
--   admin   / 123456   超管，可管所有门店
--   store1  / 123456   望京店店长，只能看门店 1
--   13800000001 / 123456   普通顾客
-- =====================================================================
INSERT INTO `t_employee` (`username`, `password`, `name`, `phone`, `store_id`, `role`, `status`)
VALUES ('admin', 'e10adc3949ba59abbe56e057f20f883e', '超级管理员', '13800000000', NULL, 1, 1),
       ('store1', 'e10adc3949ba59abbe56e057f20f883e', '望京店店长', '13800000001', 1, 2, 1);

INSERT INTO `t_user` (`phone`, `password`, `nickname`)
VALUES ('13800000001', 'e10adc3949ba59abbe56e057f20f883e', '测试用户');

INSERT INTO `t_store` (`name`, `address`, `phone`, `open_time`, `close_time`, `notice`, `status`)
VALUES ('望京SOHO店', '北京市朝阳区望京SOHO T1 B1-108', '010-88886666', '09:00:00', '21:30:00',
        '本店高峰期出餐约需 15 分钟，请耐心等待', 1),
       ('中关村店', '北京市海淀区中关村大街 1 号 3 层', '010-66668888', '10:00:00', '22:00:00',
        '新店开业，全场 9 折', 1);

-- 两个门店都有"招牌热菜"，用来验证 store_id 隔离是否真的生效
INSERT INTO `t_category` (`store_id`, `name`, `sort`, `status`)
VALUES (1, '招牌热菜', 1, 1),
       (1, '主食', 2, 1),
       (1, '饮品', 3, 1),
       (2, '招牌热菜', 1, 1),
       (2, '小吃', 2, 1);

-- "冰镇酸梅汤"是下架状态，用来验证用户端是否过滤 status=1
INSERT INTO `t_dish` (`store_id`, `category_id`, `name`, `price`, `image`, `description`, `status`, `sort`)
VALUES (1, 1, '宫保鸡丁', 38.00, '/upload/gongbao.jpg', '花生米脆，微辣回甜', 1, 1),
       (1, 1, '麻婆豆腐', 28.00, '/upload/mapo.jpg', '嫩豆腐，麻辣鲜香', 1, 2),
       (1, 2, '扬州炒饭', 22.00, '/upload/chaofan.jpg', '粒粒分明，配虾仁', 1, 1),
       (1, 2, '手工水饺', 26.00, '/upload/jiaozi.jpg', '猪肉白菜馅，12 只', 1, 2),
       (1, 3, '鲜榨橙汁', 15.00, '/upload/orange.jpg', '现榨，无添加糖', 1, 1),
       (1, 3, '冰镇酸梅汤', 12.00, '/upload/suanmei.jpg', '乌梅熬制，解腻（已下架）', 0, 2),
       (2, 4, '黑椒牛柳', 48.00, '/upload/niuliu.jpg', '澳洲牛柳，黑椒汁', 1, 1),
       (2, 5, '炸鸡块', 18.00, '/upload/jikuai.jpg', '外酥里嫩，6 块装', 1, 1);


-- =====================================================================
-- 自检（选中单独执行）
-- =====================================================================
-- SHOW TABLES;                                                 -- 期望 9 张
-- SELECT COUNT(*) AS dish_cnt FROM t_dish;                     -- 期望 8
-- SELECT COUNT(*) AS off_shelf FROM t_dish WHERE status = 0;   -- 期望 1
-- SHOW INDEX FROM t_dish;
-- SHOW INDEX FROM t_orders;
