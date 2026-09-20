<template>
  <div class="coupon-page">
    <van-nav-bar title="我的优惠券" left-arrow fixed placeholder @click-left="router.back()" />

    <van-skeleton v-if="loading" title :row="4" style="padding: 16px" />

    <template v-else>
      <van-empty v-if="coupons.length === 0" description="还没有优惠券" />

      <div v-else class="coupon-list">
        <div
          v-for="coupon in coupons"
          :key="coupon.id"
          class="coupon"
          :class="{ 'coupon-inactive': coupon.status !== 0 }"
        >
          <div class="amount">
            <span class="symbol">¥</span>
            <span class="value">{{ formatAmount(coupon.amount) }}</span>
            <span class="threshold">满{{ formatAmount(coupon.threshold) }}可用</span>
          </div>

          <div class="detail">
            <p class="expire">有效期至 {{ formatDate(coupon.expireTime) }}</p>
            <van-tag :type="statusTag(coupon.status).type" plain>
              {{ statusTag(coupon.status).text }}
            </van-tag>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchMyCoupons } from '../api/user'

const router = useRouter()

const coupons = ref([])
const loading = ref(true)

/**
 * Mirrors the server's status enum on t_user_coupon:
 * 0 unused, 1 locked by an order, 2 used, 3 expired.
 */
const STATUS_MAP = {
  0: { text: '未使用', type: 'primary' },
  1: { text: '已锁定', type: 'warning' },
  2: { text: '已使用', type: 'default' },
  3: { text: '已过期', type: 'danger' }
}

onMounted(async () => {
  try {
    coupons.value = await fetchMyCoupons()
  } catch {
    coupons.value = []
  } finally {
    loading.value = false
  }
})

function statusTag(status) {
  return STATUS_MAP[status] || { text: '未知', type: 'default' }
}

/** Drops a trailing `.00` so ¥30 reads as "30" and ¥30.5 stays "30.5". */
function formatAmount(amount) {
  const value = Number(amount)
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function formatDate(value) {
  if (!value) {
    return '-'
  }
  // Backend sends ISO-ish "2026-10-20T19:31:10.001"; only the date matters here.
  return String(value).slice(0, 10)
}
</script>

<style scoped>
.coupon-list {
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.coupon {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  border-left: 4px solid var(--van-primary-color);
}

.coupon-inactive {
  border-left-color: #dcdee0;
  opacity: 0.6;
}

.amount {
  width: 116px;
  flex-shrink: 0;
  padding: 16px 8px;
  text-align: center;
  color: var(--van-primary-color);
}

.coupon-inactive .amount {
  color: var(--mp-text-muted);
}

.symbol {
  font-size: 14px;
}

.value {
  font-size: 28px;
  font-weight: 600;
}

.threshold {
  display: block;
  font-size: 12px;
  color: var(--mp-text-muted);
  margin-top: 2px;
}

.detail {
  flex: 1;
  padding: 16px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.expire {
  margin: 0;
  font-size: 13px;
  color: var(--mp-text-muted);
}
</style>
