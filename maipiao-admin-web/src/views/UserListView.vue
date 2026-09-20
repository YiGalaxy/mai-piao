<template>
  <div>
    <div class="page-head">
      <h2>用户</h2>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-input
          v-model="keyword"
          placeholder="手机号前缀或昵称"
          clearable
          style="width: 220px"
          @keyup.enter="reload"
        />
        <el-select v-model="status" placeholder="状态" clearable style="width: 120px">
          <el-option label="正常" :value="1" />
          <el-option label="已停用" :value="0" />
        </el-select>
        <el-select v-model="role" placeholder="角色" clearable style="width: 130px">
          <el-option label="普通用户" value="USER" />
          <el-option label="管理员" value="ADMIN" />
        </el-select>
        <el-button type="primary" @click="reload">查询</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" empty-text="没有匹配的用户">
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column prop="nickname" label="昵称" min-width="120" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'ADMIN' ? 'danger' : 'info'">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '正常' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="180">
          <template #default="{ row }">{{ shortTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link @click="onToggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link @click="onToggleRole(row)">
              {{ row.role === 'ADMIN' ? '降为普通' : '设为管理员' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPage"
      />
    </el-card>

    <el-drawer v-model="detailOpen" title="用户详情" size="420px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="手机号">{{ detail.phone }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ detail.nickname }}</el-descriptions-item>
          <el-descriptions-item label="角色">
            {{ detail.role === 'ADMIN' ? '管理员' : '普通用户' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ detail.status === 1 ? '正常' : '已停用' }}
          </el-descriptions-item>
          <el-descriptions-item label="注册时间">
            {{ shortTime(detail.createTime) }}
          </el-descriptions-item>
          <!--
            Cancelled and refunded orders count for neither number. "Has this
            person bought anything" does not mean "have they ever clicked buy".
          -->
          <el-descriptions-item label="有效订单">{{ detail.orderCount }} 笔</el-descriptions-item>
          <el-descriptions-item label="累计金额">¥{{ detail.paidAmount }}</el-descriptions-item>
        </el-descriptions>

        <el-button
          type="primary"
          style="margin-top: 16px; width: 100%"
          @click="router.push({ path: '/orders', query: { userId: detail.id } })"
        >
          查看该用户的订单
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchUser, fetchUsers, setUserRole, setUserStatus } from '../api/admin'

const router = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const status = ref(null)
const role = ref(null)
const loading = ref(true)

const detailOpen = ref(false)
const detail = ref(null)

onMounted(reload)

async function reload() {
  page.value = 1
  await load()
}

async function load() {
  loading.value = true
  try {
    const result = await fetchUsers({
      keyword: keyword.value || undefined,
      status: status.value ?? undefined,
      role: role.value || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = result.rows || []
    total.value = result.total || 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  page.value = p
  load()
}

async function openDetail(row) {
  detail.value = null
  detailOpen.value = true
  try {
    detail.value = await fetchUser(row.id)
  } catch {
    detailOpen.value = false
  }
}

async function onToggleStatus(row) {
  const disabling = row.status === 1
  try {
    await ElMessageBox.confirm(
      disabling
        ? `停用 ${row.phone}？该账号将无法登录，已发出的登录状态也会失效。`
        : `启用 ${row.phone}？`,
      disabling ? '停用账号' : '启用账号',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await setUserStatus(row.id, disabling ? 0 : 1)
    ElMessage.success('已更新')
    await load()
  } catch {
    // surfaced - the server refuses disabling yourself, or the last admin
  }
}

async function onToggleRole(row) {
  const promoting = row.role !== 'ADMIN'
  try {
    await ElMessageBox.confirm(
      promoting
        ? `把 ${row.phone} 设为管理员？他们可以管理演出、场馆、用户和订单。`
        : `撤销 ${row.phone} 的管理员权限？`,
      '修改角色',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await setUserRole(row.id, promoting ? 'ADMIN' : 'USER')
    ElMessage.success('已更新')
    await load()
  } catch {
    // surfaced - the server refuses revoking your own, or the last one
  }
}

function shortTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.filters {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
