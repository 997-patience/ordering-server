import { defineStore } from 'pinia'
import { ref } from 'vue'

// 顾客和管理员各自有独立令牌，避免切换身份时互相覆盖。
export const useTokenStore = defineStore('token', () => {
  const userToken = ref(localStorage.getItem('ordering_user_token') || '')
  const adminToken = ref(localStorage.getItem('ordering_admin_token') || '')
  function setToken(kind, token) {
    localStorage.setItem(`ordering_${kind}_token`, token)
    if (kind === 'admin') adminToken.value = token
    else userToken.value = token
  }
  function removeToken(kind) {
    localStorage.removeItem(`ordering_${kind}_token`)
    if (kind === 'admin') adminToken.value = ''
    else userToken.value = ''
  }
  return { userToken, adminToken, setToken, removeToken }
})
