import axios from 'axios'
import { ElMessage } from 'element-plus'

// 开发环境由 Vite 把 /api 代理到后端；响应成功时直接返回 data。
const request = axios.create({ baseURL: '/api', timeout: 15000 })

request.interceptors.request.use((config) => {
  const kind = config.url.startsWith('/admin') ? 'admin' : 'user'
  const token = localStorage.getItem(`ordering_${kind}_token`)
  if (token) config.headers.Authorization = token
  return config
})

request.interceptors.response.use(
  (response) => {
    if (response.data?.code === 0) return response.data.data
    const error = new Error(response.data?.message || '操作失败')
    ElMessage.error(error.message)
    return Promise.reject(error)
  },
  (error) => {
    if (error.response?.status === 401) {
      const kind = error.config?.url?.startsWith('/admin') ? 'admin' : 'user'
      localStorage.removeItem(`ordering_${kind}_token`)
      ElMessage.error('登录已失效，请重新登录')
      if (!location.pathname.startsWith('/login')) location.assign(`/login/${kind}`)
    } else {
      ElMessage.error(error.response?.data?.message || '无法连接服务器，请检查后端是否启动')
    }
    return Promise.reject(error)
  },
)

export default request
