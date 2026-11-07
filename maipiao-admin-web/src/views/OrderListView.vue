<template>
  <div>
    <div class="page-head">
      <h2>订单</h2>
    </div>

    <p class="hint">
      只读。改订单意味着动钱或者发出没付过款的座位，两者都需要各自的流程 ——
      退款、补发 —— 而不是一个通用的编辑框。能悄悄改写订单的后台也没法审计。
    </p>

    <el-card shadow="never">
      <!--
        Phone first, because that is what an administrator has when a customer
        rings up. The order number is usually the thing they do not have.
      -->
      <div class="filters">
        <el-input
          v-model="filters.phone"
          placeholder="手机号"
          clearable
          style="width: 160px"
          @keyup.enter="reload"
        />
        <el-input
          v-model="filters.orderNo"
          placeholder="订单号前缀"
          clearable
          style="width: 200px"
          @keyup.enter="reload"
        />
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 140px">
          <el-option
            v-for="s in STATUSES"
            :key="s.value"
            :label="s.label"
            :value="s.value"
          />
        </el-select>
        <el-date-picker
          v-model="filters.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开演起"
          end-placeholder="开演止"
          style="width: 260px"
        />
        <el-button type="primary" @click="reload">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" empty-text="没有匹配的订单">
        <el-table-column label="订单号" width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="openDetail(row.orderNo)">
              {{ row.orderNo }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="买家" width="130" />
        <el-table-column prop="projectTitle" label="项目" min-width="200" />
        <el-table-column prop="venueName" label="场馆" min-width="140" />
        <el-table-column label="开演" width="130">
          <template #default="{ row }">{{ shortTime(row.showTime) }}</template>
        </el-table-column>
        <el-table-column prop="seatCount" label="票数" width="70" />
        <el-table-column label="实付" width="100">
          <template #default="{ row }">
            <span class="amount">¥{{ row.payAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下单时间" width="150">
          <template #default="{ row }">{{ shortTime(row.createTime) }}</template>
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

    <el-drawer v-model="detailOpen" title="订单详情" size="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="订单号">{{ detail.order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusName }}</el-descriptions-item>
          <el-descriptions-item label="项目">{{ detail.order.projectTitle }}</el-descriptions-item>
          <el-descriptions-item label="场馆">
            {{ detail.order.venueName }} · {{ detail.order.placeName }}
          </el-descriptions-item>
          <el-descriptions-item label="开演">{{ shortTime(detail.order.showTime) }}</el-descriptions-item>
          <el-descriptions-item label="座位">
            <span v-for="(item, i) in detail.items" :key="item.id">
              <el-tag size="small" style="margin: 2px">{{ item.seatLabel }}</el-tag>
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="金额">
            ¥{{ detail.order.payAmount }}
            <span class="muted" v-if="Number(detail.order.discountAmount) > 0">
              （原价 ¥{{ detail.order.totalAmount }}，优惠 ¥{{ detail.order.discountAmount }}）
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="支付时间">
            {{ detail.order.payTime ? shortTime(detail.order.payTime) : '未支付' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="sub">票码</h4>
        <el-table :data="detail.items" size="small">
          <el-table-column prop="seatLabel" label="座位" width="110" />
          <el-table-column label="票档价" width="100">
            <template #default="{ row }">¥{{ row.price }}</template>
          </el-table-column>
          <el-table-column label="票码" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.ticketNo || '未出票' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="验票" width="90">
            <template #default="{ row }">
              {{ row.checkStatus === 1 ? '已验' : '未验' }}
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { fetchOrder, fetchOrders } from '../api/admin'

const route = useRoute()

const STATUSES = [
  { value: 0, label: '待支付' },
  { value: 1, label: '支付中' },
  { value: 2, label: '已支付' },
  { value: 3, label: '已完成' },
  { value: 4, label: '已取消' },
  { value: 5, label: '退款中' },
  { value: 6, label: '已退款' }
]

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(true)

const filters = reactive({
  phone: '',
  orderNo: '',
  status: null,
  range: null
})

const detailOpen = ref(false)
const detail = ref(null)

onMounted(() => {
  // 从用户详情页跳过来时会带上用户；但手机号才是管理员会去填的筛选项，
  // 所以这里把 id 当作一个隐藏筛选项透传下去，而不是反查成手机号。
  if (route.query.userId) {
    filters.userId = route.query.userId
  }
  load()
})

function reload() {
  page.value = 1
  load()
}

function reset() {
  filters.phone = ''
  filters.orderNo = ''
  filters.status = null
  filters.range = null
  filters.userId = undefined
  reload()
}

async function load() {
  loading.value = true
  try {
    const result = await fetchOrders({
      phone: filters.phone || undefined,
      orderNo: filters.orderNo || undefined,
      userId: filters.userId || undefined,
      status: filters.status ?? undefined,
      from: filters.range?.[0] || undefined,
      to: filters.range?.[1] || undefined,
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

async function openDetail(orderNo) {
  detail.value = null
  detailOpen.value = true
  try {
    detail.value = await fetchOrder(orderNo)
  } catch {
    detailOpen.value = false
  }
}

function statusType(status) {
  if (status === 2 || status === 3) return 'success'
  if (status === 4 || status === 6) return 'info'
  if (status === 5) return 'warning'
  return 'danger'
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
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.amount {
  color: #ff6700;
  font-weight: 600;
}

.muted {
  font-size: 13px;
  color: var(--mp-text-muted);
}

.mono {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.sub {
  margin: 20px 0 10px;
  font-size: 15px;
}
</style>
