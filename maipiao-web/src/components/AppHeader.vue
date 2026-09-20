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
        <router-link
          to="/films"
          :class="{ active: isCategory('MOVIE') }"
        >
          电影
        </router-link>
        <router-link
          to="/films?category=CONCERT"
          :class="{ active: isStage() }"
        >
          演出
        </router-link>
        <router-link
          v-if="userStore.isLoggedIn"
          to="/orders"
          :class="{ active: route.path.startsWith('/orders') }"
        >
          我的订单
        </router-link>
      </nav>

      <div class="search">
        <el-input v-model="keyword" placeholder="搜索影片、影院、演员" clearable @keyup.enter="onSearch">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>

      <div class="user">
        <template v-if="userStore.isLoggedIn">
          <el-dropdown @command="onCommand">
            <span class="user-name">
              <span class="avatar">{{ avatarText }}</span>
              {{ userStore.displayName }}
              <el-icon class="caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="orders">我的订单</el-dropdown-item>
                <el-dropdown-item command="coupons">我的优惠券</el-dropdown-item>
                <el-dropdown-item command="profile">账户设置</el-dropdown-item>
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
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Location, Search } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const keyword = ref('')

const avatarText = computed(() => {
  const name = userStore.profile?.nickname
  return name ? name.slice(0, 1) : '我'
})

/** True when the film list is showing films - "演出" covers everything else. */
function isCategory(value) {
  return route.path.startsWith('/films') && (route.query.category ?? 'MOVIE') === value
}

/** Any non-film category, so one nav item covers concerts, comedy and stage. */
function isStage() {
  return route.path.startsWith('/films') && route.query.category && route.query.category !== 'MOVIE'
}

function onSearch() {
  const value = keyword.value.trim()
  if (!value) return
  router.push({ path: '/films', query: { q: value } })
}

async function onCommand(command) {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'coupons') {
    router.push('/coupons')
  } else if (command === 'orders') {
    router.push('/orders')
  } else if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return
    }
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/')
  }
}
</script>

<style scoped>
/**
 * White bar, not a dark one.
 *
 * The category's headers are red-and-white: a white surface carrying the brand
 * colour in the logo and in the selected nav item. A dark bar reads as a
 * developer console, which is not what this is.
 */
.app-header {
  background: #fff;
  border-bottom: 1px solid var(--mp-border);
  height: 64px;
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
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #ff8a33, var(--mp-primary));
  box-shadow: 0 3px 8px rgba(255, 103, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  font-size: 19px;
}

.logo-text {
  font-size: 21px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--mp-text-strong);
}

.city {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: var(--mp-text-muted);
  flex-shrink: 0;
  cursor: pointer;
}

.city:hover {
  color: var(--mp-primary);
}

.nav {
  display: flex;
  gap: 24px;
  font-size: 15px;
  flex-shrink: 0;
}

/**
 * Selected nav: coloured text plus a short coloured rule under it.
 *
 * A short rule rather than a full-width underline - the bar reads as one
 * continuous surface, and the marker reads as attached to the word.
 */
.nav a {
  position: relative;
  color: var(--mp-text);
  padding: 4px 0 8px;
  transition: color 0.2s;
}

.nav a::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%) scaleX(0);
  width: 20px;
  height: 3px;
  border-radius: 2px;
  background: var(--mp-primary);
  transition: transform 0.22s ease;
}

.nav a:hover {
  color: var(--mp-primary);
}

.nav a.active {
  color: var(--mp-primary);
  font-weight: 600;
}

.nav a.active::after {
  transform: translateX(-50%) scaleX(1);
}

.search {
  flex: 1;
  max-width: 340px;
}

.search :deep(.el-input__wrapper) {
  border-radius: var(--mp-radius-pill);
  background: #f5f5f5;
  box-shadow: none;
  padding: 1px 14px;
}

.search :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--mp-primary) inset;
}

.user {
  margin-left: auto;
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--mp-text);
  cursor: pointer;
  outline: none;
}

.user-name:hover {
  color: var(--mp-primary);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--mp-primary-soft);
  color: var(--mp-primary);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.caret {
  font-size: 12px;
}
</style>
