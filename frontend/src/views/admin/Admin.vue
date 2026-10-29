<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { money, orderStatus } from '../../utils/display'
import { useTokenStore } from '../../stores/token'
import {
  adminInfoService,
  adminLogoutService,
  storePageService,
  storeAddService,
  storeUpdateService,
  storeDeleteService,
  categoryListService,
  categoryAddService,
  categoryUpdateService,
  categoryDeleteService,
  dishPageService,
  dishAddService,
  dishUpdateService,
  dishStatusService,
  dishDeleteService,
  adminOrderPageService,
  adminOrderAcceptService,
  adminOrderCompleteService,
  adminOrderCancelService,
  todayStatsService,
  uploadService,
} from '../../api/admin'
const tokenStore = useTokenStore()
const route = useRoute()
const router = useRouter()
const section = computed(() => route.params.section || 'overview')
const me = ref({})
const stats = ref({})
const items = ref([])
const categories = ref([])
const stores = ref([])
const selectedStore = ref(null)
const page = ref(1)
const total = ref(0)
const search = ref('')
const status = ref('')
const categoryFilter = ref('')
// 使用本地日期，避免 UTC 转换导致跨天。
function todayRange() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  const today = `${year}-${month}-${day}`
  return { begin: `${today}T00:00:00`, end: `${today}T23:59:59` }
}
const initialRange = todayRange()
const beginTime = ref(initialRange.begin)
const endTime = ref(initialRange.end)
const dialog = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = reactive({})
const menus = [
  ['overview', '经营概览'],
  ['orders', '订单管理'],
  ['dishes', '菜品管理'],
  ['categories', '分类管理'],
  ['stores', '门店管理'],
]
const title = computed(() => menus.find((x) => x[0] === section.value)?.[1] || '经营概览')
const isSuper = computed(() => me.value.role === 1)
const canStoreManage = computed(() => !!me.value.storeId)
async function refresh() {
  const s = section.value
  if (['overview', 'dishes', 'orders'].includes(s) && !canStoreManage.value) {
    items.value = []
    return
  }
  if (s === 'overview') {
    stats.value = await todayStatsService()
    return
  }
  if (s === 'categories') {
    if (isSuper.value && !selectedStore.value) {
      items.value = []
      return
    }
    items.value = await categoryListService(isSuper.value ? { storeId: selectedStore.value } : {})
    return
  }
  if (s === 'stores') {
    const r = await storePageService({
      pageNum: page.value,
      pageSize: 10,
      name: search.value || undefined,
      status: status.value === '' ? undefined : status.value,
    })
    items.value = r.items
    total.value = r.total
    return
  }
  if (s === 'dishes') {
    const r = await dishPageService({
      pageNum: page.value,
      pageSize: 10,
      name: search.value || undefined,
      status: status.value === '' ? undefined : status.value,
      categoryId: categoryFilter.value || undefined,
    })
    items.value = r.items
    total.value = r.total
    return
  }
  if (s === 'orders') {
    const format = (t) => (t ? t.replace('T', ' ') + (t.length === 16 ? ':00' : '') : undefined)
    const r = await adminOrderPageService({
      pageNum: page.value,
      pageSize: 10,
      status: status.value === '' ? undefined : status.value,
      beginTime: format(beginTime.value),
      endTime: format(endTime.value),
    })
    items.value = r.items
    total.value = r.total
  }
}
async function loadCategories() {
  if (canStoreManage.value)
    categories.value = await categoryListService(isSuper.value ? { storeId: me.value.storeId } : {})
}
async function loadStores() {
  if (isSuper.value) {
    const r = await storePageService({ pageNum: 1, pageSize: 100 })
    stores.value = r.items
  }
}
async function init() {
  me.value = await adminInfoService()
  await Promise.all([loadCategories(), loadStores()])
  await refresh()
}
function openForm(item) {
  editing.value = !!item
  Object.keys(form).forEach((k) => delete form[k])
  if (section.value === 'stores')
    Object.assign(form, {
      name: '',
      address: '',
      phone: '',
      openTime: '09:00:00',
      closeTime: '21:00:00',
      notice: '',
      status: 1,
    })
  if (section.value === 'categories') Object.assign(form, { name: '', sort: 0, status: 1 })
  if (section.value === 'dishes')
    Object.assign(form, {
      name: '',
      categoryId: categories.value[0]?.id || null,
      price: 0,
      image: '',
      description: '',
      sort: 0,
      status: 1,
    })
  if (item) Object.assign(form, item)
  dialog.value = true
}
async function save() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (section.value === 'dishes' && !form.categoryId) {
    ElMessage.warning('请选择菜品分类')
    return
  }
  let fields =
    section.value === 'stores'
      ? ['id', 'name', 'address', 'phone', 'openTime', 'closeTime', 'notice', 'status']
      : section.value === 'categories'
        ? ['id', 'name', 'sort', 'status']
        : ['id', 'name', 'categoryId', 'price', 'image', 'description', 'status', 'sort']
  const body = Object.fromEntries(
    fields.filter((k) => form[k] !== undefined && form[k] !== null).map((k) => [k, form[k]]),
  )
  const addService = {
    stores: storeAddService,
    categories: categoryAddService,
    dishes: dishAddService,
  }[section.value]
  const updateService = {
    stores: storeUpdateService,
    categories: categoryUpdateService,
    dishes: dishUpdateService,
  }[section.value]
  saving.value = true
  try {
    await (editing.value ? updateService(body) : addService(body))
    dialog.value = false
    ElMessage.success('保存成功')
    if (section.value === 'categories') await loadCategories()
    await refresh()
  } finally {
    saving.value = false
  }
}
async function upload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    form.image = await uploadService(file)
    ElMessage.success('图片上传成功')
  } finally {
    event.target.value = ''
  }
}
async function remove(row) {
  const s = section.value
  const warning =
    s === 'stores'
      ? '删除门店会同时清除该门店分类、菜品、订单、购物车及绑定员工。此操作不可撤销，确定继续吗？'
      : `确定删除“${row.name}”吗？`
  try {
    await ElMessageBox.confirm(warning, '确认删除', {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
    })
    const deleteService = {
      stores: storeDeleteService,
      categories: categoryDeleteService,
      dishes: dishDeleteService,
    }[s]
    await deleteService(row.id)
    ElMessage.success('已删除')
    if (s === 'categories') await loadCategories()
    await refresh()
  } catch {}
}
async function toggleDish(row) {
  await dishStatusService(row.id, row.status === 1 ? 0 : 1)
  await refresh()
}
async function orderAction(row, action) {
  try {
    let body = null
    if (action === 'cancel') {
      const result = await ElMessageBox.prompt('请输入拒单原因', '拒绝订单', {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        inputPattern: /\S+/,
        inputErrorMessage: '请填写原因',
      })
      body = { cancelReason: result.value }
    } else
      await ElMessageBox.confirm(action === 'accept' ? '确定接单？' : '确定完成订单？', '确认操作', {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
      })
    if (action === 'accept') await adminOrderAcceptService(row.id)
    else if (action === 'complete') await adminOrderCompleteService(row.id)
    else await adminOrderCancelService(row.id, body)
    ElMessage.success('操作成功')
    await refresh()
  } catch {}
}
async function logout() {
  try {
    await adminLogoutService()
  } finally {
    tokenStore.removeToken('admin')
    router.push('/login/admin')
  }
}
function navigate(s) {
  router.push(`/admin/${s}`)
}
function searchEntries() {
  page.value = 1
  refresh()
}
watch(section, async () => {
  page.value = 1
  search.value = ''
  status.value = ''
  categoryFilter.value = ''
  const range = todayRange()
  beginTime.value = range.begin
  endTime.value = range.end
  items.value = []
  try {
    await refresh()
  } catch {}
})
onMounted(() => init().catch(() => {}))
</script>
<template>
  <div class="admin-layout">
    <aside class="admin-sidebar">
      <router-link class="logo" to="/"><i>●</i> 食光 <small>商家工作台</small></router-link>
      <div class="side-label">门店管理</div>
      <button
        v-for="m in menus"
        :key="m[0]"
        :class="{ active: section === m[0] }"
        @click="navigate(m[0])"
      >
        {{ m[1] }}
      </button>
      <div class="admin-side-footer"><router-link to="/">返回顾客端</router-link></div>
    </aside>
    <div class="admin-main">
      <header class="admin-top">
        <div><span class="subtle">商家工作台 / </span>{{ title }}</div>
        <div>
          {{ me.name || me.username || '管理员' }}
          <button class="link" @click="logout">退出登录</button>
        </div>
      </header>
      <main class="admin-content">
        <div class="page-title">
          <div>
            <p class="eyebrow">STORE WORKSPACE</p>
            <h1>{{ title }}</h1>
            <p class="subtle">
              {{ section === 'overview' ? '了解今天的经营情况。' : '让日常管理更加清晰。' }}
            </p>
          </div>
          <button
            v-if="
              (section === 'stores' && isSuper) ||
              (['categories', 'dishes'].includes(section) && canStoreManage)
            "
            class="primary"
            @click="openForm()"
          >
            ＋ 新增{{ section === 'stores' ? '门店' : section === 'categories' ? '分类' : '菜品' }}
          </button>
        </div>
        <template v-if="section === 'overview'"
          ><div v-if="!canStoreManage" class="info-banner">
            当前账号未绑定门店，无法查看门店经营数据。可前往门店管理。
          </div>
          <div v-if="canStoreManage" class="stats-grid">
            <div class="stat">
              <span>今日订单</span><b>{{ stats.orderCount ?? 0 }} <small>单</small></b>
            </div>
            <div class="stat">
              <span>今日营业额</span><b>¥ {{ money(stats.turnover) }}</b>
            </div>
            <div class="stat">
              <span>待接单</span><b>{{ stats.pendingCount ?? 0 }} <small>单</small></b>
            </div>
            <div class="stat">
              <span>已完成</span><b>{{ stats.finishedCount ?? 0 }} <small>单</small></b>
            </div>
            <div class="stat">
              <span>已取消</span><b>{{ stats.canceledCount ?? 0 }} <small>单</small></b>
            </div>
          </div>
          <div class="panel welcome">
            <h2>工作台</h2>
            <p class="subtle">使用左侧导航处理订单、维护菜品和门店信息。</p>
            <button class="secondary" @click="navigate('orders')">查看订单 →</button>
          </div></template
        ><template v-else
          ><div
            v-if="isSuper && ['categories', 'dishes', 'orders'].includes(section)"
            class="info-banner"
          >
            {{
              section === 'categories'
                ? '超级管理员可选择门店查看分类；分类增删改由门店管理员操作。'
                : '此业务由绑定门店的管理员处理，权限以服务器为准。'
            }}
          </div>
          <div class="panel">
            <div class="filters">
              <template v-if="['stores', 'dishes'].includes(section)"
                ><input
                  v-model.trim="search"
                  placeholder="按名称搜索"
                  @keyup.enter="searchEntries" /></template
              ><select v-if="['stores', 'dishes', 'orders'].includes(section)" v-model="status">
                <option value="">全部状态</option>
                <template v-if="section === 'orders'"
                  ><option :value="1">待接单</option>
                  <option :value="2">已接单</option>
                  <option :value="3">已完成</option>
                  <option :value="4">已取消</option></template
                ><template v-else
                  ><option :value="1">{{ section === 'stores' ? '营业中' : '上架' }}</option>
                  <option :value="0">
                    {{ section === 'stores' ? '已打烊' : '下架' }}
                  </option></template
                ></select
              ><select v-if="section === 'dishes'" v-model="categoryFilter">
                <option value="">全部分类</option>
                <option v-for="c in categories" :key="c.id" :value="c.id">
                  {{ c.name }}
                </option></select
              ><select v-if="section === 'categories' && isSuper" v-model="selectedStore">
                <option :value="null">请选择门店</option>
                <option v-for="s in stores" :key="s.id" :value="s.id">{{ s.name }}</option></select
              ><template v-if="section === 'orders'"
                ><label>开始 <input v-model="beginTime" type="datetime-local" step="1" /></label
                ><label>结束 <input v-model="endTime" type="datetime-local" step="1" /></label></template
              ><button
                class="secondary"
                @click="searchEntries"
              >
                查询
              </button>
            </div>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr v-if="section === 'stores'">
                    <th>门店</th>
                    <th>地址 / 电话</th>
                    <th>营业时间</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                  <tr v-else-if="section === 'categories'">
                    <th>分类名称</th>
                    <th>排序</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                  <tr v-else-if="section === 'dishes'">
                    <th>菜品</th>
                    <th>分类</th>
                    <th>价格</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                  <tr v-else>
                    <th>订单号</th>
                    <th>金额</th>
                    <th>取餐方式</th>
                    <th>时间</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in items" :key="row.id">
                    <template v-if="section === 'stores'"
                      ><td>
                        <b>{{ row.name }}</b
                        ><small>{{ row.notice }}</small>
                      </td>
                      <td>
                        {{ row.address || '—' }}<small>{{ row.phone || '—' }}</small>
                      </td>
                      <td>{{ row.openTime || '—' }} - {{ row.closeTime || '—' }}</td>
                      <td>{{ row.status === 1 ? '营业中' : '已打烊' }}</td>
                      <td>
                        <button class="link" @click="openForm(row)">编辑</button
                        ><button v-if="isSuper" class="link danger" @click="remove(row)">
                          删除
                        </button>
                      </td></template
                    ><template v-else-if="section === 'categories'"
                      ><td>{{ row.name }}</td>
                      <td>{{ row.sort }}</td>
                      <td>{{ row.status === 1 ? '启用' : '禁用' }}</td>
                      <td>
                        <template
                          v-if="canStoreManage && (!isSuper || selectedStore === me.storeId)"
                          ><button class="link" @click="openForm(row)">编辑</button
                          ><button class="link danger" @click="remove(row)">删除</button></template
                        >
                      </td></template
                    ><template v-else-if="section === 'dishes'"
                      ><td>{{ row.name }}</td>
                      <td>
                        {{
                          categories.find((c) => c.id === row.categoryId)?.name || row.categoryId
                        }}
                      </td>
                      <td>¥ {{ money(row.price) }}</td>
                      <td>{{ row.status === 1 ? '上架' : '下架' }}</td>
                      <td>
                        <button class="link" @click="openForm(row)">编辑</button
                        ><button class="link" @click="toggleDish(row)">
                          {{ row.status === 1 ? '下架' : '上架' }}</button
                        ><button class="link danger" @click="remove(row)">删除</button>
                      </td></template
                    ><template v-else
                      ><td>{{ row.orderNo }}</td>
                      <td>¥ {{ money(row.amount) }}</td>
                      <td>{{ row.pickupType === 1 ? '自取' : '堂食' }}</td>
                      <td>{{ row.createTime }}</td>
                      <td>{{ orderStatus(row.status) }}</td>
                      <td>
                        <template v-if="row.status === 1"
                          ><button class="link" @click="orderAction(row, 'accept')">接单</button
                          ><button class="link danger" @click="orderAction(row, 'cancel')">
                            拒单
                          </button></template
                        ><button
                          v-if="row.status === 2"
                          class="link"
                          @click="orderAction(row, 'complete')"
                        >
                          完成
                        </button>
                      </td></template
                    >
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-if="!items.length" class="empty-state">暂无数据</div>
            <el-pagination
              v-if="section !== 'categories' && total > 10"
              layout="prev, pager, next"
              :total="total"
              :page-size="10"
              v-model:current-page="page"
              @current-change="refresh"
            /></div
        ></template>
      </main>
    </div>
    <el-dialog
      v-model="dialog"
      :title="
        (editing ? '编辑' : '新增') +
        (section === 'stores' ? '门店' : section === 'categories' ? '分类' : '菜品')
      "
      width="min(560px, 94vw)"
      ><form @submit.prevent="save">
        <label class="field">名称<input v-model.trim="form.name" required maxlength="50" /></label
        ><template v-if="section === 'stores'"
          ><label class="field">地址<input v-model="form.address" /></label
          ><label class="field">电话<input v-model="form.phone" /></label>
          <div class="form-row">
            <label class="field"
              >营业时间<input v-model="form.openTime" type="time" step="1" /></label
            ><label class="field"
              >打烊时间<input v-model="form.closeTime" type="time" step="1"
            /></label>
          </div>
          <label class="field">公告<textarea v-model="form.notice"></textarea></label></template
        ><template v-if="section === 'dishes'"
          ><label class="field"
            >分类<select v-model="form.categoryId" required>
              <option :value="null">请选择分类</option>
              <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select></label
          ><label class="field"
            >价格（元）<input
              v-model.number="form.price"
              type="number"
              min="0"
              step="0.01"
              required /></label
          ><label class="field"
            >菜品图片<input type="file" accept="image/*" @change="upload" /><small v-if="form.image"
              >已上传：{{ form.image }}</small
            ></label
          ><label class="field"
            >菜品描述<textarea v-model="form.description"></textarea></label></template
        ><label v-if="section !== 'stores'" class="field"
          >排序<input v-model.number="form.sort" type="number" min="0" /></label
        ><label class="field"
          >状态<select v-model.number="form.status">
            <option :value="1">
              {{ section === 'stores' ? '营业中' : section === 'dishes' ? '上架' : '启用' }}
            </option>
            <option :value="0">
              {{ section === 'stores' ? '已打烊' : section === 'dishes' ? '下架' : '禁用' }}
            </option>
          </select></label
        >
        <div class="dialog-actions">
          <button type="button" class="secondary" @click="dialog = false">取消</button
          ><button class="primary" :disabled="saving">保存</button>
        </div>
      </form></el-dialog
    >
  </div>
</template>

