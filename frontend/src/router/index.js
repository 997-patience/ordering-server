import { createRouter, createWebHistory } from 'vue-router'
import Customer from '../views/customer/Customer.vue'
import Admin from '../views/admin/Admin.vue'
import Login from '../views/Login.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: Customer, meta: { auth: 'user' } },
    { path: '/orders', component: Customer, meta: { auth: 'user' } },
    { path: '/admin/:section?', component: Admin, meta: { auth: 'admin' } },
    { path: '/login/:kind?', component: Login },
  ],
})

// 先检查对应身份是否登录；有效性仍由后端拦截器校验。
router.beforeEach((to) => {
  if (to.meta.auth && !localStorage.getItem(`ordering_${to.meta.auth}_token`)) {
    return { path: `/login/${to.meta.auth}`, query: { next: to.fullPath } }
  }
})

export default router
