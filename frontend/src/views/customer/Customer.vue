<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { foodImage, money, orderStatus } from '../../utils/display'
import { useTokenStore } from '../../stores/token'
import {
  customerStoreListService,
  customerCategoryListService,
  customerDishListService,
  customerDishDetailService,
  cartListService,
  cartAddService,
  cartUpdateService,
  cartDeleteService,
  orderSubmitService,
  customerOrderListService,
  customerOrderDetailService,
  customerOrderCancelService,
} from '../../api/customer'
const tokenStore = useTokenStore()
const route = useRoute()
const router = useRouter()
const stores = ref([])
const store = ref(null)
const categories = ref([])
const category = ref(null)
const dishes = ref([])
const cart = ref([])
const orders = ref([])
const orderTotal = ref(0)
const page = ref(1)
const detail = ref(null)
const dishDetail = ref(null)
const checkout = ref(false)
const busy = ref(false)
const pickup = ref(1)
const pickupTime = ref('')
const remark = ref('')
const total = computed(() => cart.value.reduce((sum, x) => sum + Number(x.amount || 0), 0))
const isOrders = computed(() => route.path === '/orders')
async function loadStores() {
  stores.value = await customerStoreListService()
  if (stores.value.length) {
    store.value = stores.value[0].id
    await loadStore()
  }
}
async function loadStore() {
  category.value = null
  categories.value = await customerCategoryListService({ storeId: store.value })
  await Promise.all([loadDishes(), loadCart()])
}
async function loadDishes() {
  dishes.value = await customerDishListService({
    storeId: store.value,
    categoryId: category.value || undefined,
  })
}
function selectCategory(id) {
  category.value = id
  loadDishes()
}
async function addFromDetail() {
  await add(dishDetail.value)
  dishDetail.value = null
}
async function loadCart() {
  cart.value = await cartListService({ storeId: store.value })
}
async function add(d) {
  await cartAddService({ dishId: d.id, quantity: 1 })
  await loadCart()
  ElMessage.success('已加入购物车')
}
async function change(item, delta) {
  if (item.quantity + delta <= 0) {
    await cartDeleteService(item.id)
  } else {
    await cartUpdateService({ dishId: item.dishId, quantity: item.quantity + delta })
  }
  await loadCart()
}
async function remove(item) {
  await cartDeleteService(item.id)
  await loadCart()
}
async function openDish(item) {
  dishDetail.value = await customerDishDetailService(item.id)
}
async function submit() {
  if (!cart.value.length) return
  busy.value = true
  try {
    const body = { storeId: store.value, pickupType: pickup.value, remark: remark.value }
    if (pickupTime.value) body.pickupTime = pickupTime.value.replace('T', ' ') + ':00'
    const result = await orderSubmitService(body)
    checkout.value = false
    remark.value = ''
    pickupTime.value = ''
    await loadCart()
    ElMessage.success(`订单 ${result.orderNo} 已提交`)
    router.push('/orders')
  } finally {
    busy.value = false
  }
}
async function loadOrders() {
  const result = await customerOrderListService({ pageNum: page.value, pageSize: 10 })
  orders.value = result.items
  orderTotal.value = result.total
}
async function openOrder(item) {
  detail.value = await customerOrderDetailService(item.id)
}
async function cancel(item) {
  try {
    await ElMessageBox.confirm('确定取消这笔订单吗？', '取消订单', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    await customerOrderCancelService(item.id)
    detail.value = null
    await loadOrders()
    ElMessage.success('订单已取消')
  } catch {}
}
function logout() {
  tokenStore.removeToken('user')
  router.push('/login/user')
}
watch(isOrders, async (value) => {
  if (value) await loadOrders()
})
onMounted(async () => {
  try {
    if (isOrders.value) await loadOrders()
    else await loadStores()
  } catch {}
})
</script>
<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="top-inner">
        <router-link class="logo" to="/"><i>●</i> 食光</router-link>
        <nav>
          <router-link to="/">点餐</router-link><router-link to="/orders">我的订单</router-link>
        </nav>
        <div class="top-actions">
          <router-link to="/admin">商家管理</router-link
          ><button class="link" @click="logout">退出</button>
        </div>
      </div>
    </header>
    <main class="container">
      <div class="breadcrumb">首页 / {{ isOrders ? '我的订单' : '门店点餐' }}</div>
      <template v-if="!isOrders"
        ><div class="page-title">
          <div>
            <p class="eyebrow">GOOD FOOD, GOOD DAY</p>
            <h1>选好门店，开始点餐</h1>
            <p class="subtle">分类清晰，挑选菜品更加方便。</p>
          </div>
          <label class="store-select"
            >当前门店
            <select v-model="store" @change="loadStore">
              <option v-for="s in stores" :key="s.id" :value="s.id">{{ s.name }}</option>
            </select></label
          >
        </div>
        <div v-if="!stores.length" class="empty-state">暂无可用门店</div>
        <div v-else class="customer-grid">
          <aside class="category-box">
            <h3>菜品分类</h3>
            <button
              :class="{ active: !category }"
              @click="selectCategory(null)"
            >
              全部菜品</button
            ><button
              v-for="c in categories"
              :key="c.id"
              :class="{ active: category === c.id }"
              @click="selectCategory(c.id)"
            >
              {{ c.name }}
            </button>
          </aside>
          <section>
            <div class="section-heading">
              <h2>{{ categories.find((c) => c.id === category)?.name || '全部菜品' }}</h2>
              <span>{{ dishes.length }} 道菜品</span>
            </div>
            <div v-if="!dishes.length" class="empty-state">暂无菜品</div>
            <article v-for="d in dishes" :key="d.id" class="dish-row">
              <button class="dish-image-button" @click="openDish(d)">
                <img
                  v-if="foodImage(d.name, d.image)"
                  :src="foodImage(d.name, d.image)"
                  :alt="d.name"
                  @error="$event.target.style.display = 'none'"
                /><span v-else class="no-image">暂无图片</span>
              </button>
              <div class="dish-copy">
                <button class="dish-title" @click="openDish(d)">{{ d.name }}</button>
                <p>{{ d.description || '用心制作，现点现做。' }}</p>
                <div class="dish-bottom">
                  <b>¥ {{ money(d.price) }}</b
                  ><button class="primary small" @click="add(d)">加入购物车 ＋</button>
                </div>
              </div>
            </article>
            <div class="cart-box">
              <div class="section-heading">
                <h2>我的购物车</h2>
                <span>{{ cart.length }} 种菜品</span>
              </div>
              <p v-if="!cart.length" class="subtle">还没有选择菜品</p>
              <div v-for="item in cart" :key="item.id" class="cart-line">
                <span
                  >{{ item.name }} <small>¥ {{ money(item.price) }}</small></span
                >
                <div>
                  <button class="step" @click="change(item, -1)">−</button
                  ><span class="quantity">{{ item.quantity }}</span
                  ><button class="step" @click="change(item, 1)">＋</button
                  ><button class="link danger" @click="remove(item)">删除</button>
                </div>
              </div>
              <div class="cart-total">
                <span
                  >合计 <b>¥ {{ money(total) }}</b></span
                ><button class="primary" :disabled="!cart.length" @click="checkout = true">
                  去结算
                </button>
              </div>
            </div>
          </section>
        </div></template
      ><template v-else
        ><div class="page-title">
          <div>
            <p class="eyebrow">MY ORDERS</p>
            <h1>我的订单</h1>
            <p class="subtle">查看订单状态和历史记录。</p>
          </div>
        </div>
        <div v-if="!orders.length" class="empty-state">还没有订单，去挑选美味吧。</div>
        <article v-for="o in orders" :key="o.id" class="order-card">
          <div>
            <b>{{ o.storeName || '门店' }}</b>
            <p class="subtle">订单号 {{ o.orderNo }} · {{ o.createTime }}</p>
          </div>
          <div class="order-right">
            <span class="status">{{ orderStatus(o.status) }}</span
            ><strong>¥ {{ money(o.amount) }}</strong
            ><button class="secondary" @click="openOrder(o)">查看详情</button
            ><button v-if="o.status === 1" class="link danger" @click="cancel(o)">取消订单</button>
          </div>
        </article>
        <el-pagination
          v-if="orderTotal > 10"
          layout="prev, pager, next"
          :total="orderTotal"
          :page-size="10"
          v-model:current-page="page"
          @current-change="loadOrders"
      /></template>
    </main>
    <el-dialog v-model="checkout" title="确认订单" width="min(520px, 92vw)"
      ><p>
        合计：<strong>¥ {{ money(total) }}</strong>
      </p>
      <label class="field"
        >取餐方式<select v-model.number="pickup">
          <option :value="1">到店自取</option>
          <option :value="2">堂食扫码</option>
        </select></label
      ><label class="field"
        >取餐时间（可不填）<input v-model="pickupTime" type="datetime-local" /></label
      ><label class="field"
        >备注<textarea
          v-model="remark"
          maxlength="255"
          placeholder="口味或其他要求"
        ></textarea></label
      ><template #footer
        ><button class="secondary" @click="checkout = false">返回</button>
        <button class="primary" :disabled="busy" @click="submit">提交订单</button></template
      ></el-dialog
    ><el-dialog
      :model-value="!!dishDetail"
      title="菜品详情"
      width="min(500px, 92vw)"
      @close="dishDetail = null"
      ><template v-if="dishDetail"
        ><img
          v-if="foodImage(dishDetail.name, dishDetail.image)"
          class="detail-image"
          :src="foodImage(dishDetail.name, dishDetail.image)"
          :alt="dishDetail.name"
        />
        <h2>{{ dishDetail.name }}</h2>
        <p>{{ dishDetail.description || '暂无介绍' }}</p>
        <b>¥ {{ money(dishDetail.price) }}</b>
        <div class="dialog-actions">
          <button
            class="primary"
            @click="addFromDetail"
          >
            加入购物车
          </button>
        </div></template
      ></el-dialog
    ><el-dialog
      :model-value="!!detail"
      title="订单详情"
      width="min(560px, 92vw)"
      @close="detail = null"
      ><template v-if="detail"
        ><p>订单号：{{ detail.orderNo }}</p>
        <p>门店：{{ detail.storeName }}　状态：{{ orderStatus(detail.status) }}</p>
        <p>
          取餐方式：{{ detail.pickupType === 1 ? '到店自取' : '堂食扫码' }}　{{
            detail.pickupTime || ''
          }}
        </p>
        <div v-for="item in detail.details" :key="item.dishId" class="cart-line">
          <span>{{ item.dishName }} × {{ item.quantity }}</span
          ><b>¥ {{ money(item.amount) }}</b>
        </div>
        <p>
          合计：<b>¥ {{ money(detail.amount) }}</b>
        </p>
        <p v-if="detail.remark">备注：{{ detail.remark }}</p>
        <p v-if="detail.cancelReason">取消原因：{{ detail.cancelReason }}</p>
        <button v-if="detail.status === 1" class="secondary" @click="cancel(detail)">
          取消订单
        </button></template
      ></el-dialog
    >
  </div>
</template>

