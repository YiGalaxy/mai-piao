<template>
  <div class="mp-container detail-page">
    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="order">
      <!-- 状态条 -->
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
        <h2>{{ subject }}信息</h2>
        <div class="info-row">
          <span class="label">{{ subject }}</span>
          <span>{{ order.projectTitle }}</span>
        </div>
        <div class="info-row">
          <span class="label">{{ venueWord }}</span>
          <span>{{ order.venueName }} · {{ order.placeName }}</span>
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
          <el-button type="primary" size="large" :loading="paying" @click="onPay">
            立即支付
          </el-button>
          <!--
            The provider confirms asynchronously, so the user has to come back
            and say so. Without this the page would sit on "waiting to pay"
            while the payment had already succeeded.
          -->
          <el-button size="large" :loading="checking" @click="onCheckPaid">
            我已支付
          </el-button>
          <el-button size="large" @click="onCancel">取消订单</el-button>
        </template>
        <template v-else>
          <!--
            已出票的订单可以退，但窗口很窄：开场前 2 小时之前、且没验过票。
            是否可退由服务端判定 —— 退票窗口是票的性质，不是页面的判断。
          -->
          <el-button
            v-if="order.status === 2 || order.status === 3"
            type="primary"
            size="large"
            :disabled="!refundable.allowed"
            :loading="refunding"
            @click="onRefund"
          >
            {{ refundable.allowed ? '申请退款' : refundable.reason }}
          </el-button>
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
import { cancelOrder, fetchOrderDetail, fetchRefundable, requestRefund } from '../api/order'
import { collectHintOf, subjectOf, venueOf } from '../utils/eventTerms'
import { precreatePayment } from '../api/pay'

const route = useRoute()
const router = useRouter()

const detail = ref(null)
const loading = ref(true)
const remainingSeconds = ref(0)
const paying = ref(false)
const checking = ref(false)
const refunding = ref(false)
// 默认不可退：还没问到服务端之前，按钮不该看起来像能点。
const refundable = ref({ allowed: false, reason: '…', amount: 0 })
const paymentNo = ref('')

let timer = null

const order = computed(() => detail.value?.order)

/**
 * Server-side status values, see OrderStatus.
 *
 * The hint text is what makes the status useful: "待支付" alone does not tell
 * 用户该拿它怎么办、不办又会怎样。
 */
const STATUS_TEXT = {
  0: { text: '等待支付', hint: '超时未支付将自动取消，座位会释放', band: 'band-warn' },
  1: { text: '支付中', hint: '支付结果确认中，请稍候', band: 'band-warn' },
  2: { text: '出票成功', hint: '', band: 'band-ok' },
  3: { text: '已完成', hint: '感谢观影', band: 'band-muted' },
  4: { text: '已取消', hint: '订单已取消，座位已释放', band: 'band-muted' },
  5: { text: '退款中', hint: '退款处理中，将原路退回', band: 'band-warn' },
  6: { text: '已退款', hint: '退款已到账', band: 'band-muted' }
}

// 订单自己带着下单那一刻快照下来的品类，所以这两个词跟着当时买的东西走，
// 而不是跟着目录今天怎么写。
const subject = computed(() => subjectOf(order.value?.category))
const venueWord = computed(() => venueOf(order.value?.category))

const statusText = computed(() => STATUS_TEXT[order.value?.status]?.text || '未知')
const statusHint = computed(() => {
  const status = order.value?.status
  // 取票提示取决于是从哪里取，而那又取决于买的是什么 —— 演唱会没有自助取票机。
  if (status === 2) return collectHintOf(order.value?.category)
  return STATUS_TEXT[status]?.hint || ''
})
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
  await loadRefundable()
}

/**
 * 问服务端这一单能不能退。
 *
 * <p>拿不到就保持「不可退」并显示原因。默认放开会让用户点进一个必然失败的流程，
 * 而失败原因还说不清楚。
 */
async function loadRefundable() {
  const status = detail.value?.order?.status
  if (status !== 2 && status !== 3) {
    return
  }
  try {
    refundable.value = await fetchRefundable(route.params.orderNo)
  } catch {
    refundable.value = { allowed: false, reason: '暂时无法确认退票条件', amount: 0 }
  }
}

async function onRefund() {
  if (refunding.value) return
  try {
    await ElMessageBox.confirm(
      `退款 ¥${refundable.value.amount} 将原路退回，座位同时释放给其他人。确定申请吗？`,
      '申请退款',
      { confirmButtonText: '确定退款', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }

  refunding.value = true
  try {
    await requestRefund(order.value.orderNo, 'USER_REQUEST')
    ElMessage.success('退款已受理，到账后订单状态会更新')
    // 重新读一次：状态可能已经变成退款中，而那是服务端的事，不该由前端猜。
    await load()
  } catch {
    // request.js 已经提示过原因
  } finally {
    refunding.value = false
  }
}

function startCountdown() {
  if (order.value?.status !== 0 || !order.value?.lockExpireTime) return

  const deadline = new Date(String(order.value.lockExpireTime).replace(' ', 'T')).getTime()
  const tick = () => {
    remainingSeconds.value = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
    if (remainingSeconds.value <= 0 && timer) {
      clearInterval(timer)
      // 服务端有它自己的取消节奏；重新拉一次拿到的是事实，而不是在这里猜。
      load()
    }
  }
  tick()
  timer = setInterval(tick, 1000)
}

/**
 * 打开支付渠道方的收银台。
 *
 * 先创建支付单，再在新标签页里打开收银台 —— 渠道方的页面不属于这个应用，
 * 不该顶掉它，否则用户会丢掉他正在付款的那张订单。
 *
 * 对结果不做任何假设。真实渠道是异步确认的，所以这个页面靠轮询，
 * 而不是干等一个可能永远不来的跳转。
 */
async function onPay() {
  if (paying.value) return
  paying.value = true

  try {
    const payment = await precreatePayment({
      orderNo: order.value.orderNo,
      amount: order.value.payAmount,
      channel: 'MOCK'
    })

    paymentNo.value = payment.paymentNo
    window.open(payment.cashierUrl, '_blank', 'noopener')

    ElMessage.info('已打开收银台，支付完成后请点击「我已支付」')
  } catch {
    // request.js 已经提示过原因了
  } finally {
    paying.value = false
  }
}

/**
 * 问服务端：钱到了没有。
 *
 * 用轮询而不是 SSE 或 websocket，因为要传的就是一个布尔值、只到一次、
 * 在用户付完款一两秒之后 —— 为它建一条订阅通道，机械结构比问题本身还大。
 */
async function onCheckPaid() {
  if (checking.value) return
  checking.value = true

  try {
    const current = await fetchOrderDetail(route.params.orderNo)
    detail.value = current

    if (current.order.status === 2) {
      ElMessage.success('支付成功，出票完成')
      if (timer) clearInterval(timer)
    } else {
      ElMessage.warning('还没有收到支付结果，请稍候再试')
    }
  } catch {
    // request.js 已经弹过提示了，这里不用再报一次
  } finally {
    checking.value = false
  }
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
    // 返回 false 表示超时任务刚刚抢先取消了它 —— 无论哪种，结果都是用户想要的。
    ElMessage.success(changed ? '订单已取消' : '订单已取消')
    await load()
  } catch {
    // request.js 已经提示过原因了
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
