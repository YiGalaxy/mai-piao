<template>
  <header class="app-header">
    <div class="mp-container header-inner">
      <div class="logo" @click="router.push('/')">
        <span class="logo-mark">麦</span>
        <span class="logo-text">麦票</span>
      </div>

      <div class="city">
        <el-icon><Location /></el-icon>
        <span>深圳</span>
      </div>

      <nav class="nav">
        <router-link to="/" :class="{ active: route.path === '/' }">首页</router-link>
        <router-link to="/films" :class="{ active: route.path.startsWith('/films') }">
          电影
        </router-link>
      </nav>

      <div class="search">
        <el-input
          v-model="keyword"
          placeholder="搜索影片、影院"
          clearable
          @keyup.enter="onSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>

      <div class="user">
        <template v-if="userStore.isLoggedIn">
          <el-dropdown @command="onCommand">
            <span class="user-name">
              <el-icon><User /></el-icon>
              {{ userStore.displayName }}
              <el-icon class="caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">我的</el-dropdown-item>
                <el-dropdown-item command="coupons">我的优惠券</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>

        <template v-else>
          <el-button link @click="router.push('/login')">登录</el-button>
          <el-divider direction="vertical" />
          <el-button link @click="router.push('/register')">注册</el-button>
        </template>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Location, Search, User } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const keyword = ref('')

function onSearch() {
  const value = keyword.value.trim()
  if (!value) {
    return
  }
  // Search lands on the film list, which is the only searchable catalogue
  // today. Cinemas join it once the cinema page exists.
  router.push({ path: '/films', query: { q: value } })
}

async function onCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'coupons') {
    router.push('/coupons')
  } else if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return // cancelled
    }
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/')
  }
}
</script>

<style scoped>
.app-header {
  background: var(--mp-header-bg);
  color: #fff;
  height: 60px;
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-inner {
  height: 100%;
  display: flex;
  align-items: center;
  gap: 24px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  flex-shrink: 0;
}

.logo-mark {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  background: var(--mp-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 18px;
}

.logo-text {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 1px;
}

.city {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: #ddd;
  flex-shrink: 0;
}

.nav {
  display: flex;
  gap: 20px;
  font-size: 15px;
  flex-shrink: 0;
}

.nav a {
  color: #ddd;
  padding: 4px 0;
  border-bottom: 2px solid transparent;
}

.nav a:hover,
.nav a.active {
  color: #fff;
  border-bottom-color: var(--mp-primary);
}

.search {
  flex: 1;
  max-width: 320px;
}

.search :deep(.el-input__wrapper) {
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.12);
  box-shadow: none;
}

.search :deep(.el-input__inner) {
  color: #fff;
}

.search :deep(.el-input__inner::placeholder) {
  color: #999;
}

.user {
  margin-left: auto;
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.user :deep(.el-button) {
  color: #ddd;
}

.user :deep(.el-button:hover) {
  color: #fff;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #ddd;
  cursor: pointer;
  outline: none;
}

.user-name:hover {
  color: #fff;
}

.caret {
  font-size: 12px;
}
</style>
