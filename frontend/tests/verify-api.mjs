import assert from 'node:assert/strict'
globalThis.localStorage = { getItem: (key) => key === 'ordering_admin_token' ? 'admin-token' : 'user-token', removeItem() {} }
const { default: request } = await import('../src/utils/request.js')
const customer = await import('../src/api/customer.js')
const admin = await import('../src/api/admin.js')
let seen
request.defaults.adapter = async (config) => {
  seen = config
  return { data: { code: 0, data: 'ok' }, status: 200, statusText: 'OK', headers: {}, config }
}
await customer.customerDishListService({ storeId: 5, categoryId: 3 })
assert.equal(seen.url, '/app/dish/list')
assert.deepEqual(seen.params, { storeId: 5, categoryId: 3 })
assert.equal(seen.headers.Authorization, 'user-token')
await customer.cartAddService({ dishId: 8, quantity: 1 })
assert.deepEqual(JSON.parse(seen.data), { dishId: 8, quantity: 1 })
await customer.cartDeleteService(9)
assert.equal(seen.url, '/app/cart/9')
assert.equal(seen.method, 'delete')
await customer.orderSubmitService({ storeId: 5, pickupType: 1 })
assert.deepEqual(JSON.parse(seen.data), { storeId: 5, pickupType: 1 })
await admin.dishStatusService(7, 0)
assert.equal(seen.url, '/admin/dish/status/7')
assert.deepEqual(seen.params, { status: 0 })
assert.equal(seen.headers.Authorization, 'admin-token')
await admin.adminOrderCancelService(4, { cancelReason: 'test' })
assert.equal(seen.url, '/admin/order/cancel/4')
assert.deepEqual(JSON.parse(seen.data), { cancelReason: 'test' })
await admin.storeDeleteService(3)
assert.deepEqual(seen.params, { id: 3 })
console.log('接口封装检查通过：路径、参数、请求体、身份令牌。')

