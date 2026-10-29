<script setup>
import { reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { registerService, customerLoginService } from '../api/customer'
import { adminLoginService } from '../api/admin'
import { useTokenStore } from '../stores/token'
const tokenStore = useTokenStore()
const route = useRoute()
const router = useRouter()
const kind = computed(() => (route.params.kind === 'admin' ? 'admin' : 'user'))
const register = ref(false)
const busy = ref(false)
const form = reactive({ phone: '', nickname: '', username: '', password: '' })
async function submit() {
  if (
    !form.password ||
    (kind.value === 'admin' ? !form.username : !form.phone) ||
    (register.value && !form.nickname)
  ) {
    ElMessage.warning('请填写完整信息')
    return
  }
  busy.value = true
  try {
    if (kind.value === 'user' && register.value) {
      await registerService({
        phone: form.phone,
        nickname: form.nickname,
        password: form.password,
      })
      ElMessage.success('注册成功，请登录')
      register.value = false
      return
    }
    const token =
      kind.value === 'admin'
        ? await adminLoginService({ username: form.username, password: form.password })
        : await customerLoginService({ phone: form.phone, password: form.password })
    tokenStore.setToken(kind.value, token)
    router.push(
      typeof route.query.next === 'string' &&
        route.query.next.startsWith('/') &&
        !route.query.next.startsWith('//')
        ? route.query.next
        : kind.value === 'admin'
          ? '/admin'
          : '/',
    )
  } catch {
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="auth-page">
    <div class="auth-brand"><span>●</span> 食光 <small>简单点，吃得好。</small></div>
    <div class="auth-card">
      <p class="eyebrow">{{ kind === 'admin' ? 'STORE WORKSPACE' : 'GOOD FOOD, GOOD DAY' }}</p>
      <h1>{{ kind === 'admin' ? '商家登录' : register ? '创建账号' : '欢迎回来' }}</h1>
      <p class="subtle">
        {{ kind === 'admin' ? '登录管理你的门店和订单' : '登录后选择门店，发现喜欢的美味。' }}
      </p>
      <form @submit.prevent="submit">
        <label v-if="kind === 'admin'"
          >账号<input
            v-model.trim="form.username"
            autocomplete="username"
            placeholder="请输入管理员账号" /></label
        ><template v-else
          ><label
            >手机号<input
              v-model.trim="form.phone"
              autocomplete="tel"
              placeholder="请输入手机号" /></label
          ><label v-if="register"
            >昵称<input v-model.trim="form.nickname" placeholder="请输入昵称" /></label></template
        ><label
          >密码<input
            v-model="form.password"
            type="password"
            :autocomplete="register ? 'new-password' : 'current-password'"
            placeholder="请输入密码" /></label
        ><button class="primary full" :disabled="busy">
          {{ busy ? '请稍候…' : register ? '注册' : '登录' }}
        </button>
      </form>
      <div class="auth-links">
        <button v-if="kind === 'user'" class="link" @click="register = !register">
          {{ register ? '已有账号？去登录' : '没有账号？立即注册' }}</button
        ><router-link :to="kind === 'user' ? '/login/admin' : '/login/user'">{{
          kind === 'user' ? '商家登录' : '顾客登录'
        }}</router-link>
      </div>
    </div>
  </div>
</template>

