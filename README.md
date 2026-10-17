# ordering-server

基于 Java 21、Spring Boot、MyBatis、MySQL 和 Redis 的多门店点餐后端学习项目。

当前已实现管理端登录、当前员工信息、退出登录，门店分页与新增、整店删除，以及分类列表、新增、修改、删除。其他功能按接口文档继续开发。

## 本地运行

1. 准备 Java 21、MySQL 和 Redis。
2. 按实际环境配置数据库。`ordering-ddl.sql` 包含重建数据库的语句，会清空同名数据库；仅在确认目标数据库可以重建时使用。
3. 配置连接密码。可设置环境变量 `ORDERING_DB_PASSWORD`、`ORDERING_REDIS_PASSWORD`，或创建 Git 忽略的 `config/application-local.yaml`：

   ```yaml
   spring:
     datasource:
       password: ${ORDERING_DB_PASSWORD:本机MySQL密码}
     data:
       redis:
         password: ${ORDERING_REDIS_PASSWORD:本机Redis密码}
   ```

4. 在项目根目录启动 `OrderingServerApplication`，或运行：

   ```powershell
   ./mvnw.cmd spring-boot:run
   ```

默认数据库和 Redis 地址见 `src/main/resources/application.yaml`。本机配置文件由 Spring Boot 可选加载，不提交到 Git。使用本机文件时应从项目根目录启动。

## 验证

```powershell
./mvnw.cmd test
```

接口集成测试使用独立 H2 内存数据库，Redis 使用测试替身。测试不会修改业务 MySQL 数据库。

## 接口与实现说明

- `docs/多门店点餐系统接口文档-V1.0.md`：接口约定。
- `docs/2.1-门店分页查询实现讲解.md`：分页查询说明。
- `docs/2.4-整店删除实现讲解.md`：整店清理及事务说明。
- `src/main/resources/db/`：需要手动应用的外键约束脚本，不会自动执行。

`DELETE /admin/store` 会清除本店分类、菜品、历史订单及明细、购物车和绑定员工；分类单独删除时，存在关联菜品会拒绝删除。
