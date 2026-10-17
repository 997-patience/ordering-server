-- 在现有 ordering 数据库应用一次；不会被应用自动执行，不会删除业务记录。
-- 应用前应修复 t_dish.category_id 指向不存在分类的数据。
-- 已有等效外键时无需重复添加。
ALTER TABLE t_dish ADD CONSTRAINT fk_dish_category
    FOREIGN KEY (category_id) REFERENCES t_category(id) ON DELETE RESTRICT;
