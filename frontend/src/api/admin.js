import request from '../utils/request.js'

// 管理员账号
export const adminLoginService = (data) => request.post('/admin/login', data)
export const adminInfoService = () => request.get('/admin/info')
export const adminLogoutService = () => request.post('/admin/logout')

// 门店管理
export const storePageService = (params) => request.get('/admin/store/page', { params })
export const storeAddService = (data) => request.post('/admin/store', data)
export const storeUpdateService = (data) => request.put('/admin/store', data)
export const storeDeleteService = (id) => request.delete('/admin/store', { params: { id } })

// 分类管理
export const categoryListService = (params) => request.get('/admin/category/list', { params })
export const categoryAddService = (data) => request.post('/admin/category', data)
export const categoryUpdateService = (data) => request.put('/admin/category', data)
export const categoryDeleteService = (id) => request.delete('/admin/category', { params: { id } })

// 菜品管理
export const dishPageService = (params) => request.get('/admin/dish/page', { params })
export const dishAddService = (data) => request.post('/admin/dish', data)
export const dishUpdateService = (data) => request.put('/admin/dish', data)
export const dishStatusService = (id, status) =>
  request.put(`/admin/dish/status/${id}`, null, { params: { status } })
export const dishDeleteService = (id) => request.delete('/admin/dish', { params: { id } })

// 商家订单及统计
export const adminOrderPageService = (params) => request.get('/admin/order/page', { params })
export const adminOrderAcceptService = (id) => request.put(`/admin/order/accept/${id}`)
export const adminOrderCompleteService = (id) => request.put(`/admin/order/complete/${id}`)
export const adminOrderCancelService = (id, data) => request.put(`/admin/order/cancel/${id}`, data)
export const todayStatsService = () => request.get('/admin/stats/today')

// 图片上传：FormData 由浏览器设置 multipart 边界
export const uploadService = (file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/admin/upload', form)
}
