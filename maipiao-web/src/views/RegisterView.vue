<template>
  <div class="register-page">
    <van-nav-bar title="注册" left-arrow @click-left="router.back()" />

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
          v-model="form.nickname"
          name="nickname"
          label="昵称"
          maxlength="20"
          placeholder="选填，不填则用手机号代替"
        />
        <van-field
          v-model="form.password"
          name="password"
          label="密码"
          type="password"
          maxlength="32"
          placeholder="6-32 位"
          :rules="passwordRules"
        />
        <van-field
          v-model="form.confirmPassword"
          name="confirmPassword"
          label="确认密码"
          type="password"
          maxlength="32"
          placeholder="再次输入密码"
          :rules="confirmRules"
        />
      </van-cell-group>

      <div class="actions">
        <van-button
          round
          block
          type="primary"
          native-type="submit"
          :loading="loading"
          loading-text="注册中..."
        >
          注册并登录
        </van-button>
      </div>
    </van-form>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast } from 'vant'
import { useUserStore } from '../store/user'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const form = reactive({
  phone: '',
  nickname: '',
  password: '',
  confirmPassword: ''
})

const phoneRules = [
  { required: true, message: '请输入手机号' },
  { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }
]
const passwordRules = [
  { required: true, message: '请输入密码' },
  { validator: (v) => v.length >= 6 && v.length <= 32, message: '密码长度需为 6-32 位' }
]
const confirmRules = [
  { required: true, message: '请再次输入密码' },
  // Confirmation is a client-side concern only - the server has no reason to
  // receive the same string twice.
  { validator: (v) => v === form.password, message: '两次输入的密码不一致' }
]

async function onSubmit() {
  loading.value = true
  try {
    await userStore.register({
      phone: form.phone,
      password: form.password,
      nickname: form.nickname || undefined
    })
    showSuccessToast('注册成功')
    await router.replace('/')
  } catch {
    // request.js already surfaced the reason.
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
}

.actions {
  margin: 24px 16px 0;
}
</style>
