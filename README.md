# 食光点餐系统

一个多门店点餐学习项目，包含 Vue 3 顾客端和管理端，以及 Spring Boot 后端。顾客可以浏览门店、点餐和查看订单；管理员可以维护门店、分类、菜品和订单。

## 项目结构

```text
ordering-server/
├─ frontend/                 Vue 3 + Vite 前端
│  ├─ src/                   页面、路由、接口和状态管理
│  └─ 接口实现讲解.md        前后端调用链说明
├─ src/main/                 Spring Boot 后端
├─ src/test/                 后端自动测试
├─ docs/                     接口约定与功能实现讲解
├─ ordering-ddl.sql          新建测试库和演示数据的脚本
└─ pom.xml                   Maven 配置
```

## 环境准备

- Java 21、Node.js 22.18+ 或 24.12+、MySQL 8、Redis 及 npm。
- 准备数据库 `ordering`，按实际环境配置 MySQL 与 Redis。
- **`ordering-ddl.sql` 包含 `DROP DATABASE` 和 `DROP TABLE`，会删除已有数据。仅在确认可以重建的全新测试环境执行，不要在已有数据的数据库上直接运行。**
- 如已有数据库，先检查表结构与 `ordering-ddl.sql` 是否兼容。新增操作依赖表 `id` 的 `AUTO_INCREMENT`；不要用重建脚本修复已有库。

## 启动后端

在项目根目录启动 MySQL 和 Redis，按本机实际情况配置数据库密码：

```powershell
$env:ORDERING_DB_PASSWORD = '你的 MySQL 密码'
$env:ORDERING_REDIS_PASSWORD = '你的 Redis 密码' # 没有密码可留空
.\mvnw.cmd spring-boot:run
```

也可以创建不提交的 `config/application-local.yaml` 覆盖数据库连接信息。默认 MySQL 地址为 `localhost:3306`，Redis 地址为 `localhost:6379`，后端地址为 `http://localhost:8080`。图片默认写入项目根目录的 `upload/`，可用 `ORDERING_UPLOAD_DIR` 修改。

macOS/Linux 可用 `./mvnw spring-boot:run` 启动。上传目录仅用于开发，部署时应提供持久存储。

## 启动前端

新开一个终端：

```powershell
cd frontend
npm ci
npm run dev
```

打开终端打印的本地地址。Vite 将 `/api` 和 `/upload` 代理至 `http://localhost:8080`；后端地址不同时，启动前端前可设置 `ORDERING_API_TARGET`。详细说明见 [前端 README](frontend/README.md) 和 [接口实现讲解](frontend/接口实现讲解.md)。

生产环境需要分别托管前端构建产物和后端：将 `/api` 转发到后端并去除 `/api` 前缀，`/upload` 转发到后端，前端路由回退到 `index.html`。仓库本身不会自动部署。

## 验证

```powershell
# 项目根目录：后端测试使用独立 H2 和 Redis 测试替身，不修改业务数据库
.\mvnw.cmd test

# frontend 目录：前端构建及接口封装检查
cd frontend
npm run build
node tests/verify-api.mjs
```

本项目是学习项目，当前登录使用 MD5 和硬编码 JWT 签名配置；演示数据库脚本还包含测试账号，不适合直接用于生产或公开部署。请为实际部署完善密码哈希、密钥管理和访问控制。
