<template>
  <div class="mp-container queue-page">
    <div class="mp-card panel">
      <!--
        做这个排队页，是因为不做的结果更糟。两千张票、十万人同时点，
        意味着其中 99.8% 的请求注定失败，而他们从座位图上得到的失败，
        是「已售罄」以一个坏掉的页面的形式砸到脸上。排队等待是同一个结果，
        只是诚实地交付，而且几乎不花后端什么成本。
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
 * 用轮询，不用 WebSocket。
 *
 * 要更新的只是一个数字，用户在等待期间它也就变几次，而等待是以秒计的。
 * 上订阅比这个问题需要的机械多得多，而且偏偏是在几千人同时连接的页面上，
 * 还得保证断线重连能扛住。
 *
 * 两秒是刻意放慢的：服务端已经替这个用户占着队里的位置了，
 * 客户端轮询再快也抢不过谁。
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
  // 尽力而为。不退出的话，会在放行集合里占一个名额直到 token 过期，
  // 也就是五分钟 —— 但这不挡别人的路，因为放行数量是按剩余库存算的，
  // 不是按队列长度算的。
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
      // 放行之后就不要再问了：答案不会再变，token 也已经拿到手了。
      clearInterval(pollTimer)
      pollTimer = null
    }
  } catch {
    // 一次轮询失败不值得报出来 —— 两秒后就是下一次，服务端的位置也还在。
    // 每一点抖动都弹提示，只会让一个正常工作的队列看起来像坏了。
  }
}

function apply(position) {
  status.value = position.status
  ahead.value = Number(position.ahead || 0)
  total.value = Number(position.total || 0)
  expiresIn.value = Number(position.expiresIn || 0)
  rushStartTime.value = position.rushStartTime || null

  if (position.token) {
    // token 是放行的凭证，而且有效期很短，所以只放在购买页能找到它的地方，
    // 别处都不放。
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
      // 队列是按服务端的时钟开的，不是按本机的；再问一次，
      // 就是这个客户端得知已经开抢的方式。
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

/* 用户来这一页就是为了这个数字，所以给它留足地方。 */
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
