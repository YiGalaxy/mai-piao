<template>
  <div class="mp-container detail-page">
    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="order">
      <!-- Status band -->
      <div class="mp-card status-card" :class="statusBand">
        <div class="status-main">
          <span class="status-text">{{ statusText }}</span>
          <span class="status-hint">{{ statusHint }}</span>
        </div>
        <div v-if="order.status === 0" class="countdown">
          剩余支付时间 <b>{{ countdownText }}</b>
        </div>
      </div>

      <div class="mp-card panel">
        <h2>影片信息</h2>
        <div class="info-row">
          <span class="label">影片</span>
          <span>{{ order.filmName }}</span>
        </div>
        <div class="info-row">
          <span class="label">影院</span>
          <span>{{ order.cinemaName }} · {{ order.hallName }}</span>
        </div>
        <div class="info-row">
          <span class="label">场次</span>
          <span>{{ formatDateTime(order.showTime) }}</span>
        </div>
        <div class="info-row">
          <span class="label">座位</span>
          <span>{{ order.seatLabels }}</span>
        </div>
      </div>

      <div class="mp-card panel">
        <h2>订单信息</h2>
        <div class="info-row">
          <span class="label">订单号</span>
          <span class="mono">{{ order.orderNo }}</span>
        </div>
        <div class="info-row">
          <span class="label">票数</span>
          <span>{{ order.seatCount }} 张</span>
        </div>
        <div class="info-row">
          <span class="label">票价小计</span>
          <span>¥{{ order.totalAmount }}</span>
        </div>
        <div class="info-row" v-if="Number(order.discountAmount) > 0">
          <span class="label">优惠</span>
          <span class="discount">-¥{{ order.discountAmount }}</span>
        </div>
        <div class="info-row">
          <span class="label">下单时间</span>
          <span>{{ formatDateTime(order.createTime) }}</span>
        </div>
        <div class="info-row" v-if="order.payTime">
          <span class="label">支付时间</span>
          <span>{{ formatDateTime(order.payTime) }}</span>
        </div>

        <div class="total-row">
          <span>实付</span>
          <span class="pay">¥{{ order.payAmount }}</span>
        </div>
      </div>

      <div class="actions">
        <template v-if="order.status === 0">
          <el-button type="primary" size="large" @click="onPay">立即支付</el-button>
          <el-button size="large" @click="onCancel">取消订单</el-button>
        </template>
        <template v-else>
          <el-button size="large" @click="router.push('/orders')">返回订单列表</el-button>
        </template>
      </div>
    </template>

    <el-empty v-else description="订单不存在" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, fetchOrderDetail } from '../api/order'

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const loading = ref(true)
const remainingSeconds = ref(0)

let timer = null

const order = computed(() => detail.value?.order)

/**
 * Server-side status values, see OrderStatus.
 *
 * The hint text is what makes the status useful: "待支付" alone does not tell
 * the user what to do about it or what happens if they do nothing.
 */
const STATUS_TEXT = {
  0: { text: '等待支付', hint: '超时未支付将自动取消，座位会释放', band: 'band-warn' },
  1: { text: '支付中', hint: '支付结果确认中，请稍候', band: 'band-warn' },
  2: { text: '出票成功', hint: '请凭取票码到影院自助机取票', band: 'band-ok' },
  3: { text: '已完成', hint: '感谢观影', band: 'band-muted' },
  4: { text: '已取消', hint: '订单已取消，座位已释放', band: 'band-muted' },
  5: { text: '退款中', hint: '退款处理中，将原路退回', band: 'band-warn' },
  6: { text: '已退款', hint: '退款已到账', band: 'band-muted' }
}

const statusText = computed(() => STATUS_TEXT[order.value?.status]?.text || '未知')
const statusHint = computed(() => STATUS_TEXT[order.value?.status]?.hint || '')
const statusBand = computed(() => STATUS_TEXT[order.value?.status]?.band || 'band-muted')

const countdownText = computed(() => {
  const s = remainingSeconds.value
  if (s <= 0) return '0:00'
  const m = Math.floor(s / 60)
  return `${m} 分 ${String(s % 60).padStart(2, '0')} 秒`
})

onMounted(async () => {
  await load()
  startCountdown()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

async function load() {
  try {
    detail.value = await fetchOrderDetail(route.params.orderNo)
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
}

function startCountdown() {
  if (order.value?.status !== 0 || !order.value?.lockExpireTime) return

  const deadline = new Date(String(order.value.lockExpireTime).replace(' ', 'T')).getTime()
  const tick = () => {
    remainingSeconds.value = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
    if (remainingSeconds.value <= 0 && timer) {
      clearInterval(timer)
      // The server cancels on its own schedule; reloading shows the truth
      // rather than guessing at it here.
      load()
    }
  }
  tick()
  timer = setInterval(tick, 1000)
}

function onPay() {
  // Payment lands with pay-service. Until then, say so plainly rather than
  // opening a page that cannot work.
  ElMessage.info('支付功能开发中（pay-service 待接入）')
}

async function onCancel() {
  try {
    await ElMessageBox.confirm('取消后座位会立即释放，确定取消吗？', '取消订单', {
      confirmButtonText: '确定取消',
      cancelButtonText: '再想想',
      type: 'warning'
    })
  } catch {
    return
  }

  try {
    const changed = await cancelOrder(order.value.orderNo)
    // false means the timeout job cancelled it a moment earlier - the outcome
    // the user asked for either way.
    ElMessage.success(changed ? '订单已取消' : '订单已取消')
    await load()
  } catch {
    // request.js surfaced the reason
  }
}

function formatDateTime(value) {
  if (!value) return '-'
  const s = String(value).replace('T', ' ')
  return `${s.slice(0, 10)} ${s.slice(11, 16)}`
}
</script>

<style scoped>
.detail-page {
  padding-top: 24px;
  max-width: 760px;
}

.status-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px;
  margin-bottom: 16px;
  color: #fff;
}

.band-warn {
  background: linear-gradient(135deg, #ff8a33, var(--mp-primary));
}

.band-ok {
  background: linear-gradient(135deg, #34c759, #07a04a);
}

.band-muted {
  background: linear-gradient(135deg, #9aa0a6, #6b7075);
}

.status-text {
  display: block;
  font-size: 22px;
  font-weight: 600;
}

.status-hint {
  display: block;
  font-size: 13px;
  opacity: 0.9;
  margin-top: 6px;
}

.countdown {
  font-size: 13px;
  opacity: 0.95;
}

.countdown b {
  font-size: 18px;
}

.panel {
  padding: 20px 24px;
  margin-bottom: 16px;
}

.panel h2 {
  margin: 0 0 16px;
  font-size: 16px;
  color: var(--mp-text-strong);
  padding-left: 10px;
  position: relative;
}

.panel h2::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--mp-primary);
}

.info-row {
  display: flex;
  padding: 9px 0;
  font-size: 14px;
  border-bottom: 1px solid var(--mp-divider);
}

.info-row:last-of-type {
  border-bottom: none;
}

.info-row .label {
  width: 90px;
  color: var(--mp-text-muted);
  flex-shrink: 0;
}

.mono {
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
}

.discount {
  color: var(--mp-primary);
}

.total-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding-top: 16px;
  margin-top: 8px;
  border-top: 1px solid var(--mp-border);
  font-size: 14px;
}

.total-row .pay {
  font-size: 24px;
  font-weight: 700;
  color: var(--mp-primary);
}

.actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  padding-bottom: 40px;
}
</style>
