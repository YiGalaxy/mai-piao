<template>
  <el-container class="shell">
    <el-header class="top">
      <div class="brand">
        <span class="mark">麦</span>
        <span class="name">麦票 · 管理后台</span>
      </div>
      <div class="who" v-if="user">
        <span>{{ user.nickname || user.phone }}</span>
        <el-tag size="small" type="success" effect="dark">管理员</el-tag>
        <el-button link @click="logout">退出</el-button>
      </div>
    </el-header>

    <el-container>
      <el-aside width="200px">
        <el-menu :default-active="route.path" router>
          <el-menu-item index="/performances">
            <el-icon><Tickets /></el-icon>
            <span>演出管理</span>
          </el-menu-item>
          <el-menu-item index="/venues">
            <el-icon><OfficeBuilding /></el-icon>
            <span>场馆</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { OfficeBuilding, Tickets } from '@element-plus/icons-vue'
import { clearSession, getProfile } from './utils/session'

const route = useRoute()
const router = useRouter()
const user = computed(() => getProfile())

function logout() {
  clearSession()
  router.push('/login')
}
</script>

<style scoped>
.shell {
  height: 100vh;
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid var(--mp-border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #ff8a33, #ff6700);
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.name {
  font-size: 17px;
  font-weight: 600;
}

.who {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}

.el-aside {
  background: #fff;
  border-right: 1px solid var(--mp-border);
}

.el-main {
  background: #f5f6f8;
}
</style>
