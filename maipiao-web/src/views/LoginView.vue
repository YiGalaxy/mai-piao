<template>
  <div class="login-page">
    <div class="brand">
      <h1>麦票</h1>
      <p class="mp-muted">在线选座 · 即刻观影</p>
    </div>

    <van-form @submit="onSubmit">
      <van-cell-group inset>
        <van-field
          v-model="form.phone"
          name="phone"
          label="手机号"
          type="tel"
          maxlength="11"
          placeholder="请输入手机号"
          :rules="phoneRules"
        />
        <van-field
          v-model="form.password"
          name="password"
          label="密码"
          type="password"
          maxlength="32"
          placeholder="请输入密码"
          :rules="passwordRules"
        />
      </van-cell-group>

      <div class="actions">
        <van-button
          round
          block
          type="primary"
          native-type="submit"
          :loading="loading"
          loading-text="登录中..."
        >
          登录
        </van-button>
        <van-button round block plain type="primary" to="/register">
          注册新账号
        </van-button>
      </div>
    </van-form>

    <p class="hint mp-muted">
      演示账号：13800000001 ~ 13800000005，密码 123456
    </p>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast } from 'vant'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loading = ref(false)
const form = reactive({ phone: '', password: '' })

// Mirror the server's constraints. The server validates again regardless -
// this only saves a round trip on an obviously wrong input.
const phoneRules = [
  { required: true, message: '请输入手机号' },
  { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }
]
const passwordRules = [
  { required: true, message: '请输入密码' },
  { validator: (v) => v.length >= 6, message: '密码至少 6 位' }
]

async function onSubmit() {
  loading.value = true
  try {
    await userStore.login({ phone: form.phone, password: form.password })
    showSuccessToast('登录成功')

    // Return the user to wherever the guard intercepted them, defaulting to home.
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' ? redirect : '/')
  } catch {
    // request.js already surfaced the reason; nothing to add here.
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  padding-top: 64px;
}

.brand {
  text-align: center;
  margin-bottom: 32px;
}

.brand h1 {
  margin: 0 0 8px;
  font-size: 32px;
  letter-spacing: 4px;
  color: var(--van-primary-color);
}

.actions {
  margin: 24px 16px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.hint {
  text-align: center;
  margin-top: 32px;
  padding: 0 16px;
}
</style>
