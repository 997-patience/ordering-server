// 上传图片优先；没有图片时只匹配已有且名称相符的素材。
export function foodImage(name, image) {
  if (image) return image
  const value = name || ''
  if (/番茄牛肉面/.test(value)) return '/images/dish-noodles.png'
  if (/麻婆豆腐/.test(value)) return '/images/dish-tofu.png'
  if (/青椒肉丝/.test(value)) return '/images/dish-pork.png'
  if (/鸡腿饭/.test(value)) return '/images/dish-chicken.png'
  return ''
}

export const money = (value) => Number(value || 0).toFixed(2)
export const orderStatus = (value) =>
  ({ 1: '待接单', 2: '已接单', 3: '已完成', 4: '已取消' })[value] || '未知状态'
