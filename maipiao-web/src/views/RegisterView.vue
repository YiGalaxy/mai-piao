<template>
  <div class="auth-page">
    <div class="auth-card">
      <aside class="auth-banner">
        <h1>加入麦票</h1>
        <p>注册后即可选座购票</p>
        <ul class="banner-points">
          <li>注册即送 3 张优惠券</li>
          <li>订单与票券永久保存</li>
          <li>支持在线退票</li>
        </ul>
      </aside>

      <section class="auth-form">
        <h2>注册新账号</h2>

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

          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="form.nickname" maxlength="20" placeholder="选填，不填则用手机号代替" />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" show-password placeholder="6-32 位" />
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              show-password
              placeholder="再次输入密码"
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
            注册并登录
          </el-button>
        </el-form>

        <div class="auth-foot">
          已有账号？
          <router-link to="/login" class="link">直接登录</router-link>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ phone: '', nickname: '', password: '', confirmPassword: '' })

const rules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    // Confirmation is a client-side concern only - the server has no reason
    // to receive the same string twice.
    {
      validator: (_rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
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
    await userStore.register({
      phone: form.phone,
      password: form.password,
      nickname: form.nickname || undefined
    })
    ElMessage.success('注册成功')
    await router.replace('/')
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
  font-size: 30px;
  letter-spacing: 2px;
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
</style>
