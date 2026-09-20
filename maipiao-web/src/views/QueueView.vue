<template>
  <div class="mp-container queue-page">
    <div class="mp-card panel">
      <!--
        The queue screen exists because the alternative is worse. Two thousand
        tickets and a hundred thousand people clicking means 99.8% of those
        requests fail, and the failure they get from a seat map is "sold out"
        arriving as a broken page. Waiting in a line is the same outcome
        delivered honestly, and it costs the backend almost nothing.
      -->
      <template v-if="status === 'WAITING'">
        <h2>正在排队</h2>
        <p class="muted">您前面还有</p>
        <div class="rank">{{ ahead.toLocaleString() }}</div>
        <p class="muted">人，请不要关闭页面</p>

        <el-progress
          class="bar"
          :percentage="progress"
          :show-text="false"
          :stroke-width="10"
        />

        <p class="tip">
          已为您保留排队位置。轮到时将在 5 分钟内开放购买，超时需重新排队。
        </p>
      </template>

      <template v-else-if="status === 'NOT_STARTED'">
        <h2>尚未开抢</h2>
        <p class="countdown">{{ countdownText }}</p>
        <p class="muted">开抢后将自动为您排队</p>
      </template>

      <template v-else-if="status === 'PASSED'">
        <h2>轮到您了</h2>
        <p class="muted">请在 {{ expiresIn }} 秒内完成购买</p>
        <el-button type="primary" size="large" @click="goBuy">立即购买</el-button>
      </template>

      <template v-else-if="status === 'SOLD_OUT'">
        <h2>已售罄</h2>
        <p class="muted">本场演出的票已全部售出</p>
        <el-button size="large" @click="router.push('/')">看看其他演出</el-button>
      </template>

      <template v-else-if="status === 'PAUSED'">
        <h2>抢购已暂停</h2>
        <p class="muted">主办方暂停了本场售票，您的排队位置已保留</p>
      </template>

      <template v-else>
        <h2>排队信息获取中</h2>
        <p class="muted">{{ error || '请稍候' }}</p>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { joinQueue, fetchQueuePosition, leaveQueue } from '../api/queue'

const route = useRoute()
const router = useRouter()

const scheduleId = route.params.id

const status = ref('LOADING')
const ahead = ref(0)
const total = ref(0)
const expiresIn = ref(0)
const rushStartTime = ref(null)
const error = ref('')

let pollTimer = null
let countdownTimer = null
const remainingToStart = ref(0)

/**
 * Polling, not a socket.
 *
 * The update is one number that changes a few times while the user waits, and
 * the wait is measured in seconds. A subscription would be more machinery than
 * the problem needs, and it would have to survive reconnects on exactly the
 * page where thousands of people are connecting at once.
 *
 * Two seconds is deliberately unhurried: the server is holding a place in line
 * for this user, so the client is not racing anyone by polling faster.
 */
const POLL_MS = 2000

const progress = computed(() => {
  if (total.value <= 0) return 0
  const passed = Math.max(0, total.value - ahead.value)
  return Math.min(99, Math.round((passed / total.value) * 100))
})

const countdownText = computed(() => {
  const s = remainingToStart.value
  if (s <= 0) return '即将开始'
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  return h > 0
    ? `${h} 小时 ${m} 分 ${sec} 秒`
    : `${m} 分 ${sec} 秒`
})

onMounted(async () => {
  await join()
  pollTimer = setInterval(poll, POLL_MS)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (countdownTimer) clearInterval(countdownTimer)
  // Best-effort. Not leaving costs a place in the admitted set until the
  // token expires, which is five minutes - it does not block anyone else,
  // because admission is sized against the remaining stock rather than the
  // line's length.
  if (status.value === 'WAITING') {
    leaveQueue(scheduleId).catch(() => {})
  }
})

async function join() {
  try {
    apply(await joinQueue(scheduleId))
  } catch {
    status.value = 'ERROR'
    error.value = '加入排队失败，请返回重试'
  }
}

async function poll() {
  try {
    const position = await fetchQueuePosition(scheduleId)
    apply(position)
    if (position.status === 'PASSED') {
      // Stop asking once admitted: the answer cannot change, and the token is
      // already in hand.
      clearInterval(pollTimer)
      pollTimer = null
    }
  } catch {
    // A single failed poll is not worth reporting - the next one is two
    // seconds away and the server still holds the place. Surfacing every blip
    // would make a working queue look broken.
  }
}

function apply(position) {
  status.value = position.status
  ahead.value = Number(position.ahead || 0)
  total.value = Number(position.total || 0)
  expiresIn.value = Number(position.expiresIn || 0)
  rushStartTime.value = position.rushStartTime || null

  if (position.token) {
    // The token is the proof of admission and it is short-lived, so it is
    // kept where the purchase page can find it and nowhere else.
    sessionStorage.setItem('maipiao_queue_token', position.token)
  }

  if (position.status === 'NOT_STARTED') {
    startCountdown()
  } else if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

function startCountdown() {
  if (countdownTimer || !rushStartTime.value) return
  const deadline = new Date(String(rushStartTime.value).replace(' ', 'T')).getTime()

  const tick = () => {
    remainingToStart.value = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
    if (remainingToStart.value <= 0) {
      clearInterval(countdownTimer)
      countdownTimer = null
      // The line opens on the server's clock, not this one; asking again is
      // how this client finds out that it has.
      join()
    }
  }
  tick()
  countdownTimer = setInterval(tick, 1000)
}

function goBuy() {
  router.replace(`/schedules/${scheduleId}/tickets`)
}
</script>

<style scoped>
.queue-page {
  padding-top: 60px;
  max-width: 520px;
}

.panel {
  padding: 40px 32px;
  text-align: center;
}

.panel h2 {
  margin: 0 0 20px;
  font-size: 20px;
  color: var(--mp-text-strong);
}

.muted {
  margin: 6px 0;
  font-size: 14px;
  color: var(--mp-text-muted);
}

/* The one number the user is here for, so it gets the space. */
.rank {
  font-size: 56px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--mp-primary);
  font-variant-numeric: tabular-nums;
  margin: 8px 0;
}

.countdown {
  font-size: 28px;
  font-weight: 700;
  color: var(--mp-primary);
  font-variant-numeric: tabular-nums;
  margin: 12px 0;
}

.bar {
  margin: 28px 0 20px;
}

.tip {
  margin: 0;
  font-size: 12px;
  color: var(--mp-text-muted);
  line-height: 1.6;
}
</style>
