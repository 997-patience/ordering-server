# 食光点餐前端

基于 Vue 3、Vue Router、Pinia、Axios、Element Plus 和 Vite。使用 C 方案，包含顾客端与商家端。

## 启动

1. 启动 ordering-server 及其 MySQL、Redis，确认后端监听 `localhost:8080`。
2. 使用 Node.js 22.18+ 或 24.12+，在本目录运行 `npm install`，然后运行 `npm run dev`。
3. 打开终端显示的地址；顾客手机号登录或注册，商家使用后端已有的员工账号登录。

开发服务器将 `/api` 和 `/upload` 代理至 `http://localhost:8080`。后端地址不同可通过环境变量 `ORDERING_API_TARGET` 配置。生产环境需将 `/api` 代理至后端并去掉 `/api` 前缀，将 `/upload` 代理至后端；前端路由需要回退至 index.html。

顾客和管理员令牌分开保存。用户退出仅清除本地令牌：当前后端没有顾客退出接口；管理员退出调用后端接口。门店权限由后端决定。删除门店将级联清除相关数据，请谨慎操作。

## 图片

优先显示后台上传的菜品图；无图时按有限的菜名关键词匹配本地写实示例图。无法匹配时显示“暂无图片”，不会把不相符的菜品图片冒充实际菜品。要覆盖更多菜品，需要扩展素材与匹配规则或提供图片服务。

## 构建

运行 `npm run build`，输出到 `dist`。Vue 代码在 `src`：`views/customer/Customer.vue` 是顾客端，`views/admin/Admin.vue` 是商家端，`api/` 定义各接口函数，`utils/request.js` 统一处理请求，`router/` 和 `stores/` 分别管理路由与令牌。每个接口的调用链请看 `接口实现讲解.md`。

