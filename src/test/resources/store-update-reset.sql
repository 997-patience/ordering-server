-- 只还原独立 H2 数据库中的门店，禁止对业务数据库执行此测试脚本。
UPDATE t_store
SET name = CASE id WHEN 1 THEN '望京SOHO店' WHEN 2 THEN '中关村店' WHEN 3 THEN '望京夜宵店' ELSE '大ID门店' END,
    address = '原地址', phone = '010-88886666', open_time = '09:00:00', close_time = '21:30:00',
    notice = '原公告', status = CASE WHEN id = 3 THEN 0 ELSE 1 END,
    create_time = '2000-01-01 00:00:00', update_time = '2000-01-01 00:00:00';
