import request from '../utils/request.js'

// 顾客注册和登录
export const registerService = (data) => request.post('/app/register', data)
export const customerLoginService = (data) => request.post('/app/login', data)

// 门店、分类和菜品
export const customerStoreListService = () => request.get('/app/store/list')
export const customerCategoryListService = (params) => request.get('/app/category/list', { params })
export const customerDishListService = (params) => request.get('/app/dish/list', { params })
export const customerDishDetailService = (id) => request.get(`/app/dish/${id}`)

// 购物车
export const cartListService = (params) => request.get('/app/cart/list', { params })
export const cartAddService = (data) => request.post('/app/cart', data)
export const cartUpdateService = (data) => request.put('/app/cart', data)
export const cartDeleteService = (id) => request.delete(`/app/cart/${id}`)

// 顾客订单
export const orderSubmitService = (data) => request.post('/app/order/submit', data)
export const customerOrderListService = (params) => request.get('/app/order/list', { params })
export const customerOrderDetailService = (id) => request.get(`/app/order/${id}`)
export const customerOrderCancelService = (id) => request.put(`/app/order/cancel/${id}`)
