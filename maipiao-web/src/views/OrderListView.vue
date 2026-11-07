<template>
  <div class="mp-container order-page">
    <div class="mp-card panel">
      <div class="head">
        <h2>我的订单</h2>
        <el-radio-group v-model="status" size="small" @change="reload">
          <el-radio-button :value="undefined">全部</el-radio-button>
          <el-radio-button :value="0">待支付</el-radio-button>
          <el-radio-button :value="2">已支付</el-radio-button>
          <el-radio-button :value="4">已取消</el-radio-button>
        </el-radio-group>
      </div>

      <el-skeleton v-if="loading" :rows="4" animated style="padding: 20px 24px" />

      <el-empty v-else-if="orders.length === 0" description="还没有订单" />

      <div v-else class="order-list">
        <div v-for="order in orders" :key="order.orderNo" class="order-item">
          <div class="order-head">
            <span class="order-no">订单号 {{ order.orderNo }}</span>
            <span class="status" :class="statusClass(order.status)">
              {{ statusText(order.status) }}
            </span>
          </div>

          <div class="order-body">
            <div class="order-info">
              <h3>{{ order.projectTitle }}</h3>
              <p class="mp-muted">
                {{ order.venueName }} · {{ order.placeName }}
              </p>
              <p class="mp-muted">
                场次 {{ formatDateTime(order.showTime) }}
              </p>
              <p class="mp-muted">座位 {{ order.seatLabels }}</p>
            </div>

            <div class="order-money">
              <span class="amount">¥{{ order.payAmount }}</span>
              <span class="count">{{ order.seatCount }} 张</span>
            </div>

            <div class="order-actions">
              <el-button
                v-if="order.status === 0"
                type="primary"
                size="small"
                @click="router.push({ name: 'order-detail', params: { orderNo: order.orderNo } })"
              >
                去支付
              </el-button>
              <el-button
                v-else
                size="small"
                @click="router.push({ name: 'order-detail', params: { orderNo: order.orderNo } })"
              >
                查看详情
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchOrders } from '../api/order'

const router = useRouter()

const orders = ref([])
const loading = ref(true)
const status = ref(undefined)

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    orders.value = (await fetchOrders(status.value)) || []
  } catch {
    orders.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 与服务端的 OrderStatus 一一对应：
 * 0 待支付，1 支付中，2 已支付，3 已完成，4 已取消，5 退款中，6 已退款。
 */
const STATUS = {
  0: { text: '待支付', cls: 'pending' },
  1: { text: '支付中', cls: 'pending' },
  2: { text: '已支付', cls: 'done' },
  3: { text: '已完成', cls: 'muted' },
  4: { text: '已取消', cls: 'muted' },
  5: { text: '退款中', cls: 'pending' },
  6: { text: '已退款', cls: 'muted' }
}

function statusText(value) {
  return STATUS[value]?.text || '未知'
}

function statusClass(value) {
  return STATUS[value]?.cls || 'muted'
}

function formatDateTime(value) {
  if (!value) return '-'
  const s = String(value).replace('T', ' ')
  return `${s.slice(0, 10)} ${s.slice(11, 16)}`
}
</script>

<style scoped>
.order-page {
  padding-top: 24px;
}

.panel {
  padding-bottom: 8px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--mp-border);
}

.head h2 {
  margin: 0;
  font-size: 18px;
  color: var(--mp-text-strong);
}

.order-list {
  padding: 8px 24px 16px;
}

.order-item {
  border: 1px solid var(--mp-divider);
  border-radius: var(--mp-radius);
  margin-top: 14px;
  overflow: hidden;
}

.order-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: #fafafa;
  font-size: 13px;
}

.order-no {
  color: var(--mp-text-muted);
}

.status {
  font-weight: 600;
}

.status.pending {
  color: var(--mp-primary);
}

.status.done {
  color: #07c160;
}

.status.muted {
  color: var(--mp-text-muted);
}

.order-body {
  display: grid;
  grid-template-columns: 1fr 110px 100px;
  align-items: center;
  gap: 16px;
  padding: 16px;
}

.order-info h3 {
  margin: 0 0 6px;
  font-size: 16px;
}

.order-info p {
  margin: 0 0 3px;
  font-size: 13px;
}

.order-money {
  text-align: right;
}

.order-money .amount {
  display: block;
  font-size: 19px;
  font-weight: 700;
  color: var(--mp-primary);
}

.order-money .count {
  font-size: 12px;
  color: var(--mp-text-muted);
}

.order-actions {
  text-align: right;
}
</style>
