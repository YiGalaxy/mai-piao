<template>
  <div class="mp-container checkout-page">
    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="seatMap">
      <div class="layout">
        <!-- 左侧：买的是什么 -->
        <section class="main">
          <div class="mp-card panel">
            <h2>确认订单</h2>

            <div class="film-row">
              <div class="poster" :style="posterStyle">
                <span class="poster-text">{{ seatMap.projectTitle.slice(0, 2) }}</span>
              </div>
              <div class="film-info">
                <h3>{{ seatMap.projectTitle }}</h3>
                <p class="mp-muted">
                  {{ seatMap.venueName }} · {{ seatMap.placeName }}（{{ seatMap.placeType }}）
                </p>
                <p class="show-time">
                  {{ formatDate(seatMap.startTime) }} {{ formatTime(seatMap.startTime) }}
                </p>
              </div>
            </div>

            <div class="seats-row">
              <span class="label">座位</span>
              <div class="seat-tags">
                <el-tag v-for="seat in pending.seats" :key="seat.seatIndex" type="warning" effect="light">
                  {{ seat.label }}
                </el-tag>
              </div>
            </div>
          </div>

          <div class="mp-card panel">
            <h2>优惠券</h2>
            <el-empty v-if="coupons.length === 0" description="暂无可用优惠券" :image-size="60" />
            <el-radio-group v-else v-model="selectedCouponId" :disabled="!canSubmit" class="coupon-list">
              <el-radio :value="null" class="coupon-option">不使用优惠券</el-radio>
              <el-radio
                v-for="coupon in coupons"
                :key="coupon.id"
                :value="String(coupon.id)"
                class="coupon-option"
              >
                <span class="coupon-amount">¥{{ formatAmount(coupon.amount) }}</span>
                <span class="mp-muted">满 {{ formatAmount(coupon.threshold) }} 可用</span>
              </el-radio>
            </el-radio-group>
          </div>
        </section>

        <!-- 右侧：金额与操作 -->
        <aside class="side">
          <div class="mp-card panel summary">
            <h2>金额</h2>

            <div class="line">
              <span>票价</span>
              <!-- 不写「N × 单价」：一笔订单可能横跨多个票价档位，
                   没有单一单价可写。 -->
              <span>{{ seatCount }} 张</span>
            </div>
            <div class="line">
              <span>小计</span>
              <span>¥{{ totalAmount }}</span>
            </div>
            <div class="line discount" v-if="discountAmount > 0">
              <span>优惠</span>
              <span>-¥{{ discountAmount }}</span>
            </div>

            <div class="line total">
              <span>应付</span>
              <span class="pay">¥{{ payAmount }}</span>
            </div>

            <div class="countdown">
              预约剩余 <b>{{ countdownText }}</b>（重试不延长）
            </div>

            <el-button
              type="primary"
              size="large"
              class="submit"
              :loading="submitting"
              :disabled="!canSubmit"
              @click="onSubmit"
            >
              {{ creation?.state === 'RETRYABLE' ? '重试下单' : '提交订单' }}
            </el-button>

            <p class="mp-muted" role="status">{{ statusMessage }}</p>
            <el-button :loading="checking" @click="queryStatus">查询下单结果</el-button>
            <el-button link @click="router.push('/orders')">查看我的订单</el-button>
            <el-button v-if="reservationMissing" link @click="leaveMissingReservation">重新选座</el-button>
            <el-button link class="back" :disabled="submitting || checking" @click="goBack">放弃本次预约并返回选座</el-button>
          </div>
        </aside>
      </div>
    </template>

    <el-empty v-else description="选座信息已失效，请重新选座">
      <el-button type="primary" @click="router.push('/films')">重新选座</el-button>
    </el-empty>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchSeatMap, releaseSeats } from '../api/seat'
import { createOrder, fetchCreationStatus } from '../api/order'
import { fetchAvailableCoupons } from '../api/user'

const router = useRouter()

const seatMap = ref(null)
const coupons = ref([])
const loading = ref(true)
const submitting = ref(false)
const selectedCouponId = ref(null)
const remainingSeconds = ref(0)

let timer = null
let pollTimer = null
let pollAttempt = 0
let disposed = false
const checking = ref(false)
const creation = ref(null)
const reservationMissing = ref(false)
const statusMessage = ref('正在核验预约和下单结果')
const canSubmit = computed(() => !submitting.value && !checking.value && remainingSeconds.value > 0
  && creation.value?.heldVerified && ['RESERVED', 'RETRYABLE'].includes(creation.value?.state))

/**
 * 座位图交过来的那把锁。
 *
 * <p>从 sessionStorage 读而不是从查询串读：它是所持资源的凭证，而 URL 是
 * 放这种东西的错地方 —— 它会进历史记录、进 referrer 请求头，还会跟着
 * 用户复制粘贴到聊天窗口里。
 */
function readPending() {
  try { return JSON.parse(sessionStorage.getItem('maipiao_pending_seats') || '{}') }
  catch { return {} }
}
const pending = ref(readPending())

const seatCount = computed(() => pending.value.seats?.length || 0)

/**
 * 占座时 seat-service 算出来的金额。
 *
 * 这里以前是拿 seatMap.price × seatCount 重算的，而 seatMap.price 是场次的
 * 列表价 —— 也就是最便宜的那一档，详情页上显示为「¥580 起」。对于分四档
 * 卖的演唱会，这等于所有座位都按最低价收费，结算页的金额会和服务端即将
 * 创建的订单对不上。
 */
const totalAmount = computed(() => Number(pending.value.amount || 0).toFixed(2))

const discountAmount = computed(() => {
  if (!selectedCouponId.value) return 0
  const coupon = coupons.value.find((c) => String(c.id) === selectedCouponId.value)
  return coupon ? Number(coupon.amount) : 0
})

const payAmount = computed(() =>
  Math.max(0, Number(totalAmount.value) - discountAmount.value).toFixed(2)
)

const countdownText = computed(() => {
  const s = remainingSeconds.value
  if (s <= 0) return '0:00'
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
})

onMounted(async () => {
  const token = pending.value.lockToken
  if (!token) {
    loading.value = false
    return
  }

  selectedCouponId.value = pending.value.request?.couponId ?? null
  await queryStatus()
  if (disposed || creation.value?.orderNo) return
  try {
    seatMap.value = await fetchSeatMap(pending.value.scheduleId)
  } catch {
    seatMap.value = null
    loading.value = false
    return
  }

  loading.value = false

  // 优惠券只是锦上添花：拉不到也不能挡住下单，用户按原价付就是了。
  try {
    coupons.value = (await fetchAvailableCoupons(Number(totalAmount.value))) || []
  } catch {
    coupons.value = []
  }

  startCountdown()
})

onUnmounted(() => {
  disposed = true
  clearInterval(timer)
  clearTimeout(pollTimer)
})

function startCountdown() {
  clearInterval(timer)
  const tick = () => {
    // 使用服务端绝对截止时间；旧版本数据也须查询服务端，不能补发十五分钟。
    remainingSeconds.value = Math.max(0, Math.ceil((Number(pending.value.expiresAt || 0) - Date.now()) / 1000))
    if (remainingSeconds.value <= 0) clearInterval(timer)
  }
  timer = setInterval(tick, 1000)
  tick()
}

function applyResult(result) {
  if (disposed) return
  creation.value = result
  reservationMissing.value = false
  statusMessage.value = result.message
  pending.value.expiresAt = result.expiresAt
  sessionStorage.setItem('maipiao_pending_seats', JSON.stringify(pending.value))
  startCountdown()
  if (result.state === 'SUCCEEDED' && result.orderNo) {
    clearPending()
    clearInterval(timer)
    clearTimeout(pollTimer)
    router.replace({ name: 'order-detail', params: { orderNo: result.orderNo } })
    return
  }
  if (result.state === 'PROCESSING') scheduleQuery()
}

function scheduleQuery() {
  clearTimeout(pollTimer)
  if (disposed || pollAttempt >= 6) return
  const delay = Math.min(30000, 2000 * (2 ** pollAttempt++))
  pollTimer = setTimeout(() => queryStatus(), delay)
}

async function queryStatus() {
  if (checking.value || disposed) return
  checking.value = true
  try {
    applyResult(await fetchCreationStatus(pending.value.lockToken))
  } catch (error) {
    // 一次查不到或网络异常不能推导出回滚。保留 token 和原请求，继续查询。
    creation.value = null
    reservationMissing.value = error.code === 10002
    statusMessage.value = error.code === 10002
      ? '预约不存在或来自旧版本，请重新选座；如已提交请先查看我的订单'
      : '暂时无法确认下单结果，请稍后查询。原请求信息已保留'
    if (!reservationMissing.value) scheduleQuery()
  } finally {
    checking.value = false
  }
}

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  clearTimeout(pollTimer)
  pollAttempt = 0
  const request = {
    lockToken: pending.value.lockToken,
    scheduleId: pending.value.scheduleId,
    seatIndexes: pending.value.seats.map((s) => s.seatIndex),
    seatLabels: pending.value.seats.map((s) => s.label),
    couponId: selectedCouponId.value,
    revise: creation.value?.state === 'RETRYABLE'
  }
  // 发出前保存原请求；刷新后先查结果，不能改参数重发一笔未知结果的请求。
  pending.value.request = request
  sessionStorage.setItem('maipiao_pending_seats', JSON.stringify(pending.value))
  creation.value = { state: 'PROCESSING' }
  statusMessage.value = '正在提交，请等待原请求结果'
  try {
    applyResult(await createOrder(request))
  } catch {
    await queryStatus()
  } finally {
    submitting.value = false
  }
}

async function goBack() {
  if (submitting.value || checking.value) return
  const scheduleId = pending.value.scheduleId
  try {
    // 服务端会先关闭预约或取消原订单；PROCESSING/已支付会拒绝释放。
    await releaseSeats(scheduleId, pending.value.lockToken)
    clearPending()
    clearInterval(timer)
    clearTimeout(pollTimer)
    router.push(`/schedules/${scheduleId}/seats`)
  } catch {
    await queryStatus()
    ElMessage.info('请先确认原请求结果，预约信息已保留')
  }
}

function clearPending() {
  sessionStorage.removeItem('maipiao_pending_seats')
}

function leaveMissingReservation() {
  clearPending()
  router.push(`/schedules/${pending.value.scheduleId}/seats`)
}

function formatAmount(amount) {
  const value = Number(amount)
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function formatTime(value) {
  return String(value).slice(11, 16)
}

function formatDate(value) {
  return String(value).slice(0, 10)
}

const posterStyle = computed(() => {
  const id = Number(pending.value.scheduleId || 1)
  const hue = (id * 47) % 360
  return {
    background: `linear-gradient(135deg, hsl(${hue} 58% 46%), hsl(${(hue + 40) % 360} 62% 30%))`
  }
})
</script>

<style scoped>
.checkout-page {
  padding-top: 24px;
}

.layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 20px;
  align-items: start;
}

.panel {
  padding: 20px 24px;
  margin-bottom: 16px;
}

.panel h2 {
  margin: 0 0 18px;
  font-size: 17px;
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
  height: 15px;
  border-radius: 2px;
  background: var(--mp-primary);
}

/* ---- 影片信息 ---- */

.film-row {
  display: flex;
  gap: 16px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--mp-divider);
}

.poster {
  width: 76px;
  height: 106px;
  border-radius: 6px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.poster-text {
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 2px;
}

.film-info h3 {
  margin: 0 0 6px;
  font-size: 17px;
}

.film-info p {
  margin: 0 0 4px;
  font-size: 13px;
}

.show-time {
  color: var(--mp-primary);
  font-weight: 600;
  font-size: 15px !important;
  margin-top: 8px !important;
}

.seats-row {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  padding-top: 18px;
}

.seats-row .label {
  color: var(--mp-text-muted);
  font-size: 13px;
  flex-shrink: 0;
  padding-top: 4px;
}

.seat-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* ---- 优惠券 ---- */

.coupon-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.coupon-option {
  height: auto;
  padding: 10px 0;
  margin-right: 0;
}

.coupon-amount {
  color: var(--mp-primary);
  font-weight: 600;
  margin-right: 8px;
}

/* ---- 金额汇总 ---- */

.summary :deep(.el-button) {
  width: 100%;
}

.line {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 14px;
}

.discount {
  color: var(--mp-primary);
}

.total {
  border-top: 1px solid var(--mp-divider);
  margin-top: 8px;
  padding-top: 14px;
  align-items: baseline;
}

.total .pay {
  font-size: 24px;
  font-weight: 700;
  color: var(--mp-primary);
}

.countdown {
  text-align: center;
  color: var(--mp-text-muted);
  font-size: 13px;
  margin: 16px 0;
}

.countdown b {
  color: var(--mp-primary);
}

.submit {
  margin-bottom: 10px;
}

.back {
  width: 100%;
}
</style>
