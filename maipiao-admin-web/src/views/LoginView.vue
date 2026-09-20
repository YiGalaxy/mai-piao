<template>
  <div class="login-page">
    <div class="card">
      <div class="brand">
        <span class="mark">麦</span>
        <h1>麦票 · 管理后台</h1>
      </div>
      <p class="hint">
        需要管理员账号。普通账号可以登录，但后台接口会返回 403 ——
        真正的校验在网关，不在这个页面。
      </p>

      <el-form :model="form" @submit.prevent="onSubmit">
        <el-form-item>
          <el-input v-model="form.phone" placeholder="手机号" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          style="width: 100%"
          :loading="loading"
          @click="onSubmit"
        >
          登录
        </el-button>
      </el-form>

      <p class="demo">
        演示账号：13800000000 / 123456
      </p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request'
import { setStoredUser, setToken } from '../utils/session'

const route = useRoute()
const router = useRouter()

const form = reactive({ phone: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  if (loading.value) return
  loading.value = true
  try {
    const result = await request.post('/user/login', {
      phone: form.phone,
      password: form.password
    })
    setToken(result.token)
    setStoredUser(result)

    // 直接问管理端这个账号到底是不是管理员，而不是先放进来、之后再失败。
    // 普通用户会拿到网关返回的 403，这正是本页需要的答案。
    try {
      await request.get('/movie/admin/projects')
    } catch {
      ElMessage.error('该账号不是管理员，无法进入后台')
      return
    }

    router.replace(route.query.redirect || '/performances')
  } catch {
    // 原因已经由 request.js 提示过了
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #2b2f36, #14161a);
}

.card {
  width: 380px;
  padding: 36px 32px;
  border-radius: 14px;
  background: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #ff8a33, #ff6700);
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}

h1 {
  font-size: 19px;
  margin: 0;
}

.hint {
  font-size: 12px;
  color: #8a9099;
  line-height: 1.6;
  margin: 0 0 20px;
}

.demo {
  font-size: 12px;
  color: #8a9099;
  text-align: center;
  margin: 16px 0 0;
}
</style>
