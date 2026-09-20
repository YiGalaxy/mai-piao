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
        这一页刻意不放座位图。这种场次是场馆直接分配座位的；把两千个座位
        画出来让人去翻，等于承诺了一个根本不存在的选择，还会把座位图查询
        压到系统最繁忙的那条路径上。
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
 * 场次自己有购买上限就用它的，没有就用平台上限。
 *
 * 两个都是真实约束，取更小的那个。6 是座位相关接口强制的平台上限；场次可以
 * 收得更紧 —— 演示里的抢票场次只允许 2 张 —— 而超过任何一个服务端都会拒，
 * 所以控件不该把这个选项摆出来。
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
 * 问服务端分到了哪几个座位。
 *
 * 座位不在这里挑，价格也不在这里算。两者都由这次调用返回，并原样带到
 * 结算页去 —— 客户端要是自己重算其中任何一个，就是在猜只有服务端
 * 才知道的事情。
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
    // 这一档里找不到这么长的一段连座。没占走任何东西，也没出故障 ——
    // 只是要的连座张数超过了这一档能凑出来的数量，服务端返回的 message
    // 会说明最多能给几张。把这个方案摆出来，而不是干脆失败：拆不拆开
    // 是买家该做的决定，不该由我们默默替他做。
    if (e?.code === 10010) {
      await offerSplit(e.message)
      return
    }
    // 其他错误 request.js 已经提示过了
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
    // 用户退出了。票档还选着，点一下就能「换个票档」。
    return
  }

  submitting.value = true
  try {
    const result = await assignSeats({
      scheduleId,
      tierId: selectedTierId.value,
      quantity: quantity.value,
      // 这是唯一一处允许买家放弃连座的地方，而且必须先问过他。
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
    // 由 request.js 负责提示
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
