<template>
  <div class="mp-container ticket-page">
    <el-skeleton v-if="loading" :rows="5" animated />

    <template v-else-if="seatMap">
      <div class="mp-card head">
        <h2>{{ seatMap.projectTitle }}</h2>
        <p class="meta">
          {{ seatMap.venueName }} · {{ seatMap.placeName }}
        </p>
        <p class="meta">{{ formatDateTime(seatMap.startTime) }}</p>
      </div>

      <!--
        No seat map on this page, by design. The screening is one where the
        venue assigns seats; showing 2000 of them and letting someone scroll
        would promise a choice that does not exist, and would put a seat-map
        query on the busiest path in the system.
      -->
      <div class="mp-card panel">
        <h2>选择票档</h2>
        <p class="hint">本场演出由系统分配座位，同订单自动分配连座。</p>

        <div class="tier-list">
          <div
            v-for="tier in seatMap.tiers"
            :key="tier.id"
            class="tier-row"
            :class="{ active: String(tier.id) === String(selectedTierId) }"
            @click="selectedTierId = String(tier.id)"
          >
            <span class="swatch" :style="{ background: tier.color || '#ff6700' }" />
            <span class="tier-name">{{ tier.name }}</span>
            <span class="tier-price">¥{{ tier.price }}</span>
          </div>
        </div>

        <div v-if="seatMap.tiers.length === 0" class="mp-muted">
          该场次暂未开放票档
        </div>
      </div>

      <div class="mp-card panel">
        <h2>购买数量</h2>
        <div class="qty-row">
          <el-input-number
            v-model="quantity"
            :min="1"
            :max="maxQuantity"
            :disabled="!selectedTierId"
          />
          <span class="mp-muted">
            最多 {{ maxQuantity }} 张
            <template v-if="seatMap.purchaseLimit > 0">（场次限购）</template>
          </span>
        </div>
      </div>

      <div class="mp-card panel summary">
        <div class="line">
          <span>票档</span>
          <span>{{ selectedTierName || '未选择' }}</span>
        </div>
        <div class="line">
          <span>数量</span>
          <span>{{ quantity }} 张</span>
        </div>
        <div class="line total">
          <span>合计</span>
          <span class="pay">¥{{ totalAmount }}</span>
        </div>
      </div>

      <div class="actions">
        <el-button size="large" @click="router.back()">返回</el-button>
        <el-button
          type="primary"
          size="large"
          :loading="submitting"
          :disabled="!selectedTierId"
          @click="onSubmit"
        >
          确认购买
        </el-button>
      </div>
    </template>

    <el-empty v-else description="场次不存在或已下架" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchSeatMap, assignSeats } from '../api/seat'

const route = useRoute()
const router = useRouter()

const scheduleId = route.params.id
const seatMap = ref(null)
const loading = ref(true)
const submitting = ref(false)
const selectedTierId = ref('')
const quantity = ref(1)

/**
 * The session's own limit when it has one, the platform ceiling otherwise.
 *
 * Both are real constraints and the smaller one binds. Six is the platform
 * maximum the seat endpoints enforce; a screening can narrow it further -
 * the rush sale in the demo allows two - and the server rejects anything
 * over either, so the control should not offer it.
 */
const maxQuantity = computed(() => {
  const limit = Number(seatMap.value?.purchaseLimit || 0)
  return limit > 0 ? Math.min(limit, 6) : 6
})

const selectedTier = computed(
  () => seatMap.value?.tiers?.find((t) => String(t.id) === String(selectedTierId.value)) || null
)
const selectedTierName = computed(() => selectedTier.value?.name || '')
const totalAmount = computed(() =>
  selectedTier.value ? (Number(selectedTier.value.price) * quantity.value).toFixed(2) : '0.00'
)

onMounted(load)

async function load() {
  try {
    seatMap.value = await fetchSeatMap(scheduleId)
    if (seatMap.value?.tiers?.length) {
      selectedTierId.value = String(seatMap.value.tiers[0].id)
    }
  } catch {
    seatMap.value = null
  } finally {
    loading.value = false
  }
}

/**
 * Asks the server which seats we got.
 *
 * The seats are not chosen here and the price is not computed here. Both come
 * back from the call, and both are carried to the checkout as they arrived -
 * a client that recomputed either would be guessing at things only the
 * server knows.
 */
async function onSubmit() {
  if (submitting.value) return
  submitting.value = true

  try {
    const result = await assignSeats({
      scheduleId,
      tierId: selectedTierId.value,
      quantity: quantity.value,
      adjacent: true
    })

    sessionStorage.setItem(
      'maipiao_pending_seats',
      JSON.stringify({
        lockToken: result.lockToken,
        scheduleId: String(result.scheduleId),
        expireSeconds: result.expireSeconds,
        amount: result.amount,
        seats: result.seatIndexes.map((seatIndex, i) => ({
          seatIndex,
          label: result.seatLabels[i]
        }))
      })
    )

    router.push({ name: 'checkout' })
  } catch (e) {
    // No run of that length in the band. Nothing was taken and nothing broke -
    // the request was simply more seats together than this band can seat, and
    // the server's message says how many it could. Offer that rather than
    // failing flat, because splitting the party up is the buyer's call to
    // make, not ours to make silently.
    if (e?.code === 10010) {
      await offerSplit(e.message)
      return
    }
    // request.js surfaced anything else
  } finally {
    submitting.value = false
  }
}

async function offerSplit(reason) {
  try {
    await ElMessageBox.confirm(reason, '没有连座了', {
      confirmButtonText: '改为不连座',
      cancelButtonText: '换个票档',
      type: 'warning'
    })
  } catch {
    // Backed out. The band is still selected, so "换个票档" is one tap away.
    return
  }

  submitting.value = true
  try {
    const result = await assignSeats({
      scheduleId,
      tierId: selectedTierId.value,
      quantity: quantity.value,
      // The one place the buyer is allowed to give up adjacency, and only
      // after being asked.
      adjacent: false
    })

    sessionStorage.setItem(
      'maipiao_pending_seats',
      JSON.stringify({
        lockToken: result.lockToken,
        scheduleId: String(result.scheduleId),
        expireSeconds: result.expireSeconds,
        amount: result.amount,
        seats: result.seatIndexes.map((seatIndex, i) => ({
          seatIndex,
          label: result.seatLabels[i]
        }))
      })
    )

    ElMessage.warning('已按不连座出票')
    router.push({ name: 'checkout' })
  } catch {
    // surfaced by request.js
  } finally {
    submitting.value = false
  }
}

function formatDateTime(value) {
  if (!value) return '-'
  const s = String(value).replace('T', ' ')
  return `${s.slice(0, 10)} ${s.slice(11, 16)}`
}
</script>

<style scoped>
.ticket-page {
  padding-top: 24px;
  max-width: 720px;
}

.head {
  padding: 20px 24px;
  margin-bottom: 16px;
}

.head h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.head .meta {
  margin: 2px 0;
  font-size: 14px;
  color: var(--mp-text-muted);
}

.panel {
  padding: 20px 24px;
  margin-bottom: 16px;
}

.panel h2 {
  margin: 0 0 12px;
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

.hint {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--mp-text-muted);
}

.tier-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tier-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--mp-border);
  border-radius: var(--mp-radius);
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.tier-row:hover {
  border-color: var(--mp-primary);
}

.tier-row.active {
  border-color: var(--mp-primary);
  background: var(--mp-primary-soft);
}

.swatch {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  flex-shrink: 0;
}

.tier-name {
  flex: 1;
  font-size: 15px;
}

.tier-price {
  font-size: 17px;
  font-weight: 700;
  color: var(--mp-primary);
}

.qty-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.summary .line {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 14px;
}

.summary .line.total {
  border-top: 1px solid var(--mp-border);
  margin-top: 6px;
  padding-top: 14px;
}

.summary .pay {
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
