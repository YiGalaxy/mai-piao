<template>
  <div class="profile page-with-tabbar">
    <van-nav-bar title="我的" fixed placeholder />

    <div class="user-card">
      <template v-if="userStore.isLoggedIn">
        <div class="avatar">{{ avatarText }}</div>
        <div class="user-meta">
          <h3>{{ userStore.displayName }}</h3>
          <p class="mp-muted">{{ userStore.maskedPhone }}</p>
        </div>
      </template>
      <template v-else>
        <div class="avatar avatar-guest">?</div>
        <div class="user-meta">
          <h3>未登录</h3>
          <p class="mp-muted">登录后可购票和查看订单</p>
        </div>
        <van-button size="small" type="primary" round @click="router.push('/login')">
          去登录
        </van-button>
      </template>
    </div>

    <van-cell-group inset>
      <van-cell title="我的优惠券" is-link to="/coupons" icon="coupon-o" />
      <van-cell title="我的订单" is-link icon="orders-o" @click="notYet('我的订单')" />
      <van-cell title="观影历史" is-link icon="clock-o" @click="notYet('观影历史')" />
    </van-cell-group>

    <van-cell-group inset v-if="userStore.isLoggedIn">
      <van-cell title="退出登录" class="logout-cell" @click="onLogout" />
    </van-cell-group>

    <van-tabbar route>
      <van-tabbar-item to="/" icon="video-o">热映</van-tabbar-item>
      <van-tabbar-item to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { useUserStore } from '../store/user'

defineOptions({ name: 'ProfileView' })

const router = useRouter()
const userStore = useUserStore()

const avatarText = computed(() => {
  const name = userStore.profile?.nickname
  return name ? name.slice(0, 1) : '我'
})

onMounted(() => {
  // The stored profile can be stale (nickname changed elsewhere, or the
  // account was disabled). Refresh it in the background; a failure here is
  // handled by the interceptor and must not block the page from rendering.
  if (userStore.isLoggedIn) {
    userStore.refreshProfile().catch(() => {})
  }
})

async function onLogout() {
  try {
    await showConfirmDialog({ title: '退出登录', message: '确定要退出当前账号吗？' })
  } catch {
    return // user cancelled
  }
  await userStore.logout()
  showToast('已退出登录')
  router.replace('/')
}

function notYet(name) {
  showToast(`${name}功能待接入`)
}
</script>

<style scoped>
.user-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 24px 16px;
  background: #fff;
  margin-bottom: 12px;
}

.avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--van-primary-color);
  color: #fff;
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.avatar-guest {
  background: #dcdee0;
}

.user-meta {
  flex: 1;
  min-width: 0;
}

.user-meta h3 {
  margin: 0 0 4px;
  font-size: 17px;
}

.user-meta p {
  margin: 0;
}

.logout-cell {
  color: var(--van-danger-color);
  justify-content: center;
}

.logout-cell :deep(.van-cell__title) {
  text-align: center;
}
</style>
