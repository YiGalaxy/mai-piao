<template>
  <div class="mp-container coupon-page">
    <div class="mp-card panel">
      <h2>我的优惠券</h2>

      <el-skeleton v-if="loading" :rows="5" animated style="padding: 24px" />

      <el-empty v-else-if="coupons.length === 0" description="还没有优惠券" />

      <div v-else class="coupon-grid">
        <div
          v-for="coupon in coupons"
          :key="coupon.id"
          class="coupon"
          :class="{ inactive: coupon.status !== 0 }"
        >
          <div class="left">
            <div class="amount">
              <span class="symbol">¥</span>{{ formatAmount(coupon.amount) }}
            </div>
            <div class="threshold">满 {{ formatAmount(coupon.threshold) }} 可用</div>
          </div>

          <div class="right">
            <div class="expire">有效期至 {{ formatDate(coupon.expireTime) }}</div>
            <el-tag :type="statusTag(coupon.status).type" size="small" effect="plain">
              {{ statusTag(coupon.status).text }}
            </el-tag>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { fetchMyCoupons } from '../api/user'

const coupons = ref([])
const loading = ref(true)

/**
 * Mirrors the server's enum on t_user_coupon:
 * 0 unused, 1 locked by an order, 2 used, 3 expired.
 */
const STATUS_MAP = {
  0: { text: '未使用', type: 'primary' },
  1: { text: '已锁定', type: 'warning' },
  2: { text: '已使用', type: 'info' },
  3: { text: '已过期', type: 'danger' }
}

onMounted(async () => {
  try {
    coupons.value = (await fetchMyCoupons()) || []
  } catch {
    coupons.value = []
  } finally {
    loading.value = false
  }
})

function statusTag(status) {
  return STATUS_MAP[status] || { text: '未知', type: 'info' }
}

/** Drops a trailing .00 so ¥30 reads as "30" and ¥30.5 stays "30.5". */
function formatAmount(amount) {
  const value = Number(amount)
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function formatDate(value) {
  return value ? String(value).slice(0, 10) : '-'
}
</script>

<style scoped>
.coupon-page {
  padding-top: 24px;
}

.panel {
  padding-bottom: 8px;
}

.panel h2 {
  margin: 0;
  padding: 18px 24px;
  font-size: 18px;
  border-bottom: 1px solid var(--mp-border);
  color: var(--mp-text-strong);
}

.coupon-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  padding: 20px;
}

.coupon {
  display: flex;
  border: 1px solid var(--mp-border);
  border-left: 4px solid var(--mp-primary);
  border-radius: 6px;
  overflow: hidden;
}

.inactive {
  border-left-color: #ddd;
  opacity: 0.6;
}

.left {
  padding: 16px;
  text-align: center;
  min-width: 130px;
}

.amount {
  color: var(--mp-primary);
  font-size: 26px;
  font-weight: 700;
}

.inactive .amount {
  color: var(--mp-text-muted);
}

.symbol {
  font-size: 15px;
}

.threshold {
  font-size: 12px;
  color: var(--mp-text-muted);
  margin-top: 4px;
}

.right {
  flex: 1;
  padding: 16px;
  border-left: 1px dashed var(--mp-border);
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
}

.expire {
  font-size: 12px;
  color: var(--mp-text-muted);
}
</style>
