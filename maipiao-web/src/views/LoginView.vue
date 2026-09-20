<template>
  <div class="auth-page">
    <div class="auth-card">
      <aside class="auth-banner">
        <h1>麦票</h1>
        <p>在线选座 · 即刻观影</p>
        <ul class="banner-points">
          <li>实时座位图，所见即所得</li>
          <li>下单锁座，15 分钟内完成支付</li>
          <li>开场前 2 小时可自助退票</li>
        </ul>
      </aside>

      <section class="auth-form">
        <h2>账号登录</h2>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="onSubmit"
        >
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" maxlength="11" placeholder="请输入手机号" />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入密码"
              @keyup.enter="onSubmit"
            />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="submit"
            :loading="loading"
            @click="onSubmit"
          >
            登录
          </el-button>
        </el-form>

        <div class="auth-foot">
          还没有账号？
          <router-link to="/register" class="link">立即注册</router-link>
        </div>

        <el-alert type="info" :closable="false" class="demo-hint">
          <template #title>
            演示账号：13800000001 ~ 13800000005，密码 123456
          </template>
        </el-alert>
      </section>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ phone: '', password: '' })

// Mirrors the server's constraints. The server validates again regardless;
// this only saves a round trip on obviously wrong input.
const rules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' }
  ]
}

async function onSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await userStore.login({ phone: form.phone, password: form.password })
    ElMessage.success('登录成功')

    // Return the user to wherever the guard intercepted them.
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' ? redirect : '/')
  } catch {
    // request.js already surfaced the reason.
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  display: flex;
  justify-content: center;
  padding: 60px 16px;
}

.auth-card {
  display: flex;
  width: 860px;
  background: #fff;
  border-radius: var(--mp-radius);
  box-shadow: var(--mp-shadow);
  overflow: hidden;
}

.auth-banner {
  width: 340px;
  flex-shrink: 0;
  padding: 48px 36px;
  background: linear-gradient(150deg, #ff7a4d, #e54d1f);
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.auth-banner h1 {
  margin: 0 0 8px;
  font-size: 36px;
  letter-spacing: 6px;
}

.auth-banner p {
  margin: 0 0 28px;
  opacity: 0.9;
}

.banner-points {
  margin: 0;
  padding-left: 18px;
  line-height: 2;
  font-size: 13px;
  opacity: 0.92;
}

.auth-form {
  flex: 1;
  padding: 48px 44px;
}

.auth-form h2 {
  margin: 0 0 28px;
  font-size: 22px;
  color: var(--mp-text-strong);
}

.submit {
  width: 100%;
  margin-top: 8px;
}

.auth-foot {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
  color: var(--mp-text-muted);
}

.link {
  color: var(--mp-primary);
}

.link:hover {
  text-decoration: underline;
}

.demo-hint {
  margin-top: 24px;
}
</style>
