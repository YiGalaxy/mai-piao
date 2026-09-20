<template>
  <div class="mp-container checkout-page">
    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="seatMap">
      <div class="layout">
        <!-- Left: what is being bought -->
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
            <el-radio-group v-else v-model="selectedCouponId" class="coupon-list">
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

        <!-- Right: money and the action -->
        <aside class="side">
          <div class="mp-card panel summary">
            <h2>金额</h2>

            <div class="line">
              <span>票价</span>
              <!-- Not "N x unit price": a booking can span price bands, so
                   there is no single unit price to show. -->
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
              请在 <b>{{ countdownText }}</b> 内完成下单
            </div>

            <el-button
              type="primary"
              size="large"
              class="submit"
              :loading="submitting"
              @click="onSubmit"
            >
              提交订单
            </el-button>

            <el-button link class="back" @click="goBack">返回选座</el-button>
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
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchSeatMap } from '../api/seat'
import { createOrder } from '../api/order'
import { fetchAvailableCoupons } from '../api/user'

const route = useRoute()
const router = useRouter()

const seatMap = ref(null)
const coupons = ref([])
const loading = ref(true)
const submitting = ref(false)
const selectedCouponId = ref(null)
const remainingSeconds = ref(0)

let timer = null

/**
 * The lock handed over from the seat map.
 *
 * <p>Read from sessionStorage rather than the query string: it is a
 * credential for a held resource, and a URL is the wrong place for one -
 * it lands in history, in referrer headers, and in anything the user pastes
 * into a chat window.
 */
const pending = computed(() => {
  try {
    const raw = sessionStorage.getItem('maipiao_pending_seats')
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
})

const seatCount = computed(() => pending.value.seats?.length || 0)

/**
 * The amount the seat service priced when it took the hold.
 *
 * It used to be recomputed here as seatMap.price x seatCount, where
 * seatMap.price is the session's listing figure - the cheapest band, shown on
 * the detail page as "from ¥580". On a concert selling four bands that charged
 * every seat the cheapest price, and the checkout would have disagreed with
 * the order the server was about to create.
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

  try {
    seatMap.value = await fetchSeatMap(pending.value.scheduleId)
  } catch {
    seatMap.value = null
    loading.value = false
    return
  }

  loading.value = false

  // Coupons are advisory: failing to load them must not block checkout, the
  // user just pays full price.
  try {
    coupons.value = (await fetchAvailableCoupons(Number(totalAmount.value))) || []
  } catch {
    coupons.value = []
  }

  startCountdown(pending.value.expireSeconds || 900)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

function startCountdown(seconds) {
  remainingSeconds.value = seconds
  timer = setInterval(() => {
    remainingSeconds.value -= 1
    if (remainingSeconds.value <= 0) {
      clearInterval(timer)
      ElMessage.warning('选座已超时，请重新选座')
      clearPending()
      router.push(`/schedules/${pending.value.scheduleId}/seats`)
    }
  }, 1000)
}

async function onSubmit() {
  if (submitting.value) return
  submitting.value = true

  try {
    const result = await createOrder({
      lockToken: pending.value.lockToken,
      scheduleId: pending.value.scheduleId,
      seatIndexes: pending.value.seats.map((s) => s.seatIndex),
      seatLabels: pending.value.seats.map((s) => s.label),
      couponId: selectedCouponId.value,
      discountAmount: discountAmount.value
    })

    // The order exists; the seat hold has served its purpose.
    clearPending()
    if (timer) clearInterval(timer)

    ElMessage.success('下单成功')
    router.push({ name: 'order-detail', params: { orderNo: result.orderNo } })
  } catch {
    // A failure here almost always means the hold lapsed or a seat was taken.
    // Send the user back to pick again rather than leaving them on a page
    // that cannot succeed.
    clearPending()
    if (timer) clearInterval(timer)
    router.push(`/schedules/${pending.value.scheduleId}/seats`)
  } finally {
    submitting.value = false
  }
}

function goBack() {
  // The hold is deliberately not released - the user may simply be checking
  // the price, and re-picking seats would start the clock over.
  router.back()
}

function clearPending() {
  sessionStorage.removeItem('maipiao_pending_seats')
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

/* ---- film ---- */

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

/* ---- coupons ---- */

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

/* ---- summary ---- */

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
