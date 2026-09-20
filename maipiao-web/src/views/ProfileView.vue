<template>
  <div class="mp-container profile-page">
    <div class="profile-layout">
      <aside class="side mp-card">
        <div class="avatar">{{ avatarText }}</div>
        <h3>{{ userStore.displayName }}</h3>
        <p class="mp-muted">{{ userStore.maskedPhone }}</p>

        <el-button v-if="userStore.isLoggedIn" class="logout" @click="onLogout">
          退出登录
        </el-button>
      </aside>

      <section class="main">
        <div class="mp-card panel">
          <h2>我的</h2>
          <el-menu :default-active="activeMenu" router class="menu">
            <el-menu-item index="/coupons">我的优惠券</el-menu-item>
            <el-menu-item index="/orders" disabled>我的订单（待接入）</el-menu-item>
            <el-menu-item index="/history" disabled>观影历史（待接入）</el-menu-item>
          </el-menu>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const activeMenu = ref(route.path)

const avatarText = computed(() => {
  const name = userStore.profile?.nickname
  return name ? name.slice(0, 1) : '我'
})

onMounted(() => {
  // The stored profile can be stale - the nickname may have changed elsewhere,
  // or the account disabled. Refresh in the background; a failure is handled
  // by the interceptor and must not stop the page from rendering.
  if (userStore.isLoggedIn) {
    userStore.refreshProfile().catch(() => {})
  }
})

async function onLogout() {
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
</script>

<style scoped>
.profile-page {
  padding-top: 24px;
}

.profile-layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 20px;
}

.side {
  padding: 32px 20px;
  text-align: center;
  height: fit-content;
}

.avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  margin: 0 auto 14px;
  background: var(--mp-primary);
  color: #fff;
  font-size: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.side h3 {
  margin: 0 0 4px;
  font-size: 17px;
}

.side p {
  margin: 0 0 20px;
  font-size: 13px;
}

.logout {
  width: 100%;
}

.panel {
  padding: 8px 0 16px;
}

.panel h2 {
  margin: 0;
  padding: 18px 24px;
  font-size: 18px;
  border-bottom: 1px solid var(--mp-border);
  color: var(--mp-text-strong);
}

.menu {
  border-right: none;
}
</style>
