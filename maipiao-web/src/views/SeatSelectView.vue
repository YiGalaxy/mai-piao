<template>
  <div class="mp-container seat-page">
    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="seatMap">
      <!-- 场次摘要 -->
      <div class="mp-card summary">
        <div>
          <h2>{{ seatMap.projectTitle }}</h2>
          <p class="mp-muted">
            {{ seatMap.venueName }} · {{ seatMap.placeName }}（{{ seatMap.placeType }}）
          </p>
        </div>
        <div class="start-time">
          <div class="date">{{ formatDate(seatMap.startTime) }}</div>
          <div class="time">{{ formatTime(seatMap.startTime) }}</div>
        </div>
      </div>

      <div class="seat-layout">
        <!-- 座位图 -->
        <div class="mp-card map-panel">
          <div class="screen">
            <div class="screen-bar"></div>
            <span class="screen-label">银幕中央</span>
          </div>

          <div class="canvas-wrap">
            <canvas ref="canvasRef" :width="canvasWidth" :height="canvasHeight" @click="onCanvasClick"></canvas>
          </div>

          <!--
            有票价档位时，图例展示各档位及其票价 —— 这才是买票的人真正需要
            用来做决定的信息。没有档位时，退回普通的座位状态图例。
          -->
          <div v-if="tiers.length > 1" class="tier-legend">
            <div v-for="tier in tiers" :key="tier.id" class="tier-item">
              <i class="dot" :style="{ background: tier.color, opacity: 0.75 }"></i>
              <span class="tier-name">{{ tier.name }}</span>
              <span class="tier-price">¥{{ formatPrice(tier.price) }}</span>
            </div>
            <div class="tier-item">
              <i class="dot sold"></i>
              <span class="tier-name">已售</span>
            </div>
          </div>

          <div v-else class="legend">
            <span><i class="dot available"></i>可选</span>
            <span><i class="dot selected"></i>已选</span>
            <span><i class="dot sold"></i>已售</span>
            <span><i class="dot couple"></i>情侣座</span>
          </div>
        </div>

        <!-- 选座与结算面板 -->
        <aside class="mp-card panel">
          <h3>已选座位</h3>

          <div v-if="selectedSeats.length === 0" class="empty mp-muted">
            请在左侧座位图上选择座位
          </div>

          <ul v-else class="picked">
            <li v-for="(seat, index) in selectedSeats" :key="seat.seatIndex">
              <span class="face">{{ SEAT_FACES[index % SEAT_FACES.length] }}</span>
              <span class="label">{{ labelOf(seat) }}</span>
              <span class="price">¥{{ formatPrice(priceOf(seat)) }}</span>
            </li>
          </ul>

          <div class="total">
            <span>合计</span>
            <span class="amount">¥{{ totalAmount }}</span>
          </div>

          <el-button
            type="primary"
            size="large"
            class="confirm"
            :loading="locking"
            :disabled="selectedSeats.length === 0"
            @click="onConfirm"
          >
            确认选座
          </el-button>

          <p class="tip mp-muted">
            最多可选 6 个座位，锁定后请在 15 分钟内完成下单
          </p>
        </aside>
      </div>
    </template>

    <el-empty v-else description="场次不存在或已下线" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchSeatMap, lockSeats } from '../api/seat'

const route = useRoute()
const router = useRouter()

const scheduleId = route.params.id

const seatMap = ref(null)
const loading = ref(true)
const locking = ref(false)
const selected = ref([])
const canvasRef = ref(null)

/**
 * 本场次的票价档位。
 *
 * 电影只有一个档位，覆盖全部座位，座位图也就不做颜色区分。演出则会有多个
 * 档位，靠颜色才看得出分区 —— 所以只有档位数大于一时才上色。
 */
const tiers = computed(() => seatMap.value?.tiers || [])

// 几何尺寸。按 14x16 的 IMAX 厅调到不用滚动就能放下。
const SEAT_W = 30
const SEAT_H = 26
const GAP = 6
const OFFSET_X = 44
const OFFSET_Y = 20
const MAX_SELECT = 6

/** 每个已选座位一个，按选择顺序取用。 */
const SEAT_FACES = ['🐱', '🐶', '🦊', '🐼', '🐨', '🐯']

const canvasWidth = computed(() => {
  if (!seatMap.value) return 600
  return seatMap.value.colCount * (SEAT_W + GAP) + OFFSET_X + 20
})

const canvasHeight = computed(() => {
  if (!seatMap.value) return 400
  return seatMap.value.rowCount * (SEAT_H + GAP) + OFFSET_Y + 20
})

const selectedSeats = computed(() => selected.value)

/**
 * 单个座位的票价。
 *
 * 取座位自己所属档位的价格，而不是场次的标价。场次上带的是列表展示用的
 * 「起」价；VIP 区的座位比看台区贵，如果一律按标价收费，前排会被少收、
 * 后排会被多收。
 */
function priceOf(seat) {
  if (seat?.tierId) {
    const tier = tiers.value.find((t) => String(t.id) === String(seat.tierId))
    if (tier) return Number(tier.price)
  }
  return Number(seatMap.value?.price || 0)
}

const totalAmount = computed(() =>
  selected.value.reduce((sum, seat) => sum + priceOf(seat), 0).toFixed(2)
)

onMounted(async () => {
  try {
    seatMap.value = await fetchSeatMap(scheduleId)
    normaliseSeatIndexes()
    // canvas 要等 seatMap 拿到之后才进 DOM，所以这里直接画、不用 nextTick：
    // 执行到这一行时模板已经重新渲染过了。
    requestAnimationFrame(draw)
  } catch {
    seatMap.value = null
  } finally {
    loading.value = false
  }
})

/**
 * 把 seatIndex 转回数字。
 *
 * <p>后端把所有 Long 都序列化成 JSON 字符串，因为 19 位的 snowflake id
 * 经不起 JavaScript 双精度解析。seatIndex 数值很小，两种形式其实都安全，
 * 但它照样是以字符串形式传过来的 —— 而字符串下标和数字下标比较会静默地
 * 什么都匹配不上，表现就是点了座位取消不掉。
 */
function normaliseSeatIndexes() {
  if (!seatMap.value?.seats) return
  seatMap.value.seats = seatMap.value.seats.map((seat) => ({
    ...seat,
    seatIndex: Number(seat.seatIndex)
  }))
}

/**
 * 绘制整个影厅。
 *
 * 位置取自座位物理上的排号和列号，而不是 seat_index —— 这样过道列
 * （本来就没有座位）自然就空出一块，不需要任何特殊处理。
 */
function draw() {
  const canvas = canvasRef.value
  if (!canvas || !seatMap.value) return

  const ctx = canvas.getContext('2d')
  const dpr = window.devicePixelRatio || 1

  // 按设备分辨率渲染，座位边框在高分屏上才不糊。
  canvas.width = canvasWidth.value * dpr
  canvas.height = canvasHeight.value * dpr
  canvas.style.width = `${canvasWidth.value}px`
  canvas.style.height = `${canvasHeight.value}px`
  ctx.scale(dpr, dpr)
  ctx.clearRect(0, 0, canvasWidth.value, canvasHeight.value)

  const selectedIndexes = new Set(selected.value.map((s) => s.seatIndex))

  for (const seat of seatMap.value.seats) {
    const x = (seat.col - 1) * (SEAT_W + GAP) + OFFSET_X
    const y = (seat.row - 1) * (SEAT_H + GAP) + OFFSET_Y

    // 情侣座画得略宽一点，看得出是两个一组。
    const w = seat.type === 1 ? SEAT_W + GAP - 2 : SEAT_W

    const tierColor = tierColorOf(seat.tierId)

    if (seat.status === 1) {
      // 已售座位用页面背景色填充，读起来像「不在这张图上」，而不是
      // 另一种可选项。
      ctx.fillStyle = '#f0f0f0'
      ctx.strokeStyle = '#e8e8e8'
    } else if (selectedIndexes.has(seat.seatIndex)) {
      ctx.fillStyle = '#ff6700'
      ctx.strokeStyle = '#e05a00'
    } else if (seat.type === 1) {
      ctx.fillStyle = '#fff3ea'
      ctx.strokeStyle = '#ffc9a3'
    } else if (tierColor) {
      // 按票价档位着色。电影只有一个档位，所有座位看起来都一样；演唱会
      // 则一眼就能看出分区 —— 这正是要把档位带到座位上的全部理由。
      ctx.fillStyle = tint(tierColor, 0.14)
      ctx.strokeStyle = tint(tierColor, 0.5)
    } else {
      ctx.fillStyle = '#ffffff'
      ctx.strokeStyle = '#c8c9cc'
    }

    roundRect(ctx, x, y, w, SEAT_H, 4)
    ctx.fill()
    ctx.lineWidth = 1
    ctx.stroke()

    // 每个已选座位上画一个表情。
    //
    // 这是全应用里唯一一处纯装饰，但它配得上：满屏一模一样的橙色方块，
    // 一眼看不出哪几个是你选的，而已售座位和你选中的座位几乎长得一样。
    // 每个座位给一个不同的表情，「这些是我的」就变得一目了然，也让用户
    // 刚刚决定掏钱的那一刻，屏幕不至于太冷冰冰。
    if (selectedIndexes.has(seat.seatIndex)) {
      const picked = selected.value.findIndex((s) => s.seatIndex === seat.seatIndex)
      ctx.font = '15px "Apple Color Emoji", "Segoe UI Emoji", sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText(SEAT_FACES[picked % SEAT_FACES.length], x + w / 2, y + SEAT_H / 2 + 1)
    }
  }

  // 左边缘标出排号，方便对号入座。
  ctx.fillStyle = '#999'
  ctx.font = '11px sans-serif'
  ctx.textAlign = 'right'
  ctx.textBaseline = 'middle'
  for (let row = 1; row <= seatMap.value.rowCount; row++) {
    const y = (row - 1) * (SEAT_H + GAP) + OFFSET_Y + SEAT_H / 2
    ctx.fillText(String(row), OFFSET_X - 10, y)
  }
}

/**
 * 取某个票价档位的颜色，场次没有档位时返回 null。
 *
 * 只有一个档位的场次就是电影，把每个座位都涂上同一种颜色，只会让图变花
 * 却不带来任何信息 —— 所以只有存在多个档位需要区分时才上色。
 */
function tierColorOf(tierId) {
  if (!tierId || tiers.value.length < 2) return null
  const tier = tiers.value.find((t) => String(t.id) === String(tierId))
  return tier?.color || null
}

/** 把十六进制颜色加上指定透明度，用于淡填充和它的描边。 */
function tint(hex, alpha) {
  const h = hex.replace('#', '')
  const r = parseInt(h.substring(0, 2), 16)
  const g = parseInt(h.substring(2, 4), 16)
  const b = parseInt(h.substring(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + w - r, y)
  ctx.quadraticCurveTo(x + w, y, x + w, y + r)
  ctx.lineTo(x + w, y + h - r)
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h)
  ctx.lineTo(x + r, y + h)
  ctx.quadraticCurveTo(x, y + h, x, y + h - r)
  ctx.lineTo(x, y + r)
  ctx.quadraticCurveTo(x, y, x + r, y)
  ctx.closePath()
}

function onCanvasClick(event) {
  if (!seatMap.value) return

  const canvas = canvasRef.value
  const rect = canvas.getBoundingClientRect()
  const clickX = event.clientX - rect.left
  const clickY = event.clientY - rect.top

  const col = Math.floor((clickX - OFFSET_X) / (SEAT_W + GAP)) + 1
  const row = Math.floor((clickY - OFFSET_Y) / (SEAT_H + GAP)) + 1

  // 用几何反推命中，而不是把画过的矩形都记下来：网格是等距的，
  // 把布局公式反过来算就够了。
  const seat = seatMap.value.seats.find((s) => s.row === row && s.col === col)
  if (!seat) return

  if (seat.status === 1) {
    ElMessage.warning('该座位已售出')
    return
  }

  const existingAt = selected.value.findIndex((s) => s.seatIndex === seat.seatIndex)
  if (existingAt >= 0) {
    selected.value.splice(existingAt, 1)
    draw()
    return
  }

  if (selected.value.length >= MAX_SELECT) {
    ElMessage.warning(`一次最多选择 ${MAX_SELECT} 个座位`)
    return
  }

  selected.value.push(seat)
  draw()
}

async function onConfirm() {
  if (selected.value.length === 0) return

  locking.value = true
  try {
    const result = await lockSeats({
      // 保持它传过来时的字符串形态。
      //
      // 这里用 Number() 会把「id 序列化成字符串」的全部意义抹掉：
      // 2101643262211633153 放不进 double，会变成 ...200，服务端就会收到
      // 一个去锁根本不存在的场次的请求。到了对面，Jackson 会自己把字符串
      // 解析回 Long，不需要我们做任何事。
      scheduleId,
      seatIndexes: selected.value.map((s) => s.seatIndex)
    })

    // 把这次占座交给结算页。
    //
    // 用 sessionStorage 而不是 URL：lock token 是所持资源的凭证，
    // 而查询串会进浏览器历史、进 referrer 请求头，还会跟着用户
    // 复制粘贴到聊天窗口里。
    sessionStorage.setItem(
      'maipiao_pending_seats',
      JSON.stringify({
        lockToken: result.lockToken,
        scheduleId: String(result.scheduleId),
        expireSeconds: result.expireSeconds,
        // 用服务端给的金额，原样带过去而不是自己重算。座位图只知道场次的
        // 列表价，也就是最便宜的那一档 —— 结算页若拿它重算，凡是
        // 不在这档里的座位都会被少收钱。
        amount: result.amount,
        seats: selected.value.map((s) => ({
          seatIndex: s.seatIndex,
          label: `${s.row}排${s.col}座`
        }))
      })
    )

    router.push({ name: 'checkout' })
  } catch {
    // 覆盖冲突的情况：在画图到点确认之间，有人抢走了某个座位。
    // 重载一次，让用户看到当前状态，而不是他刚才盯着的旧状态。
    await reload()
  } finally {
    locking.value = false
  }
}

async function reload() {
  try {
    seatMap.value = await fetchSeatMap(scheduleId)
    normaliseSeatIndexes()
    selected.value = []
    requestAnimationFrame(draw)
  } catch {
    // 保持页面原样
  }
}

function labelOf(seat) {
  return `${seat.row}排${seat.col}座`
}

function formatPrice(value) {
  const n = Number(value)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

function formatTime(value) {
  return String(value).slice(11, 16)
}

function formatDate(value) {
  return String(value).slice(0, 10)
}
</script>

<style scoped>
.tier-legend {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 20px;
  margin-top: 28px;
  font-size: 13px;
}

.tier-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tier-name {
  color: var(--mp-text);
}

.tier-price {
  color: var(--mp-primary);
  font-weight: 600;
}

.seat-page {
  padding-top: 20px;
}

.summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  margin-bottom: 16px;
}

.summary h2 {
  margin: 0 0 6px;
  font-size: 20px;
}

.summary p {
  margin: 0;
  font-size: 13px;
}

.start-time {
  text-align: right;
}

.start-time .date {
  font-size: 13px;
  color: var(--mp-text-muted);
}

.start-time .time {
  font-size: 24px;
  font-weight: 700;
  color: var(--mp-primary);
}

.seat-layout {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 16px;
  align-items: start;
}

.map-panel {
  padding: 24px;
  overflow-x: auto;
}

.screen {
  text-align: center;
  margin-bottom: 24px;
}

.screen-bar {
  height: 8px;
  width: 60%;
  margin: 0 auto 6px;
  background: linear-gradient(to bottom, #d8d8d8, #f0f0f0);
  border-radius: 4px 4px 0 0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.screen-label {
  font-size: 12px;
  color: var(--mp-text-muted);
}

.canvas-wrap {
  display: flex;
  justify-content: center;
  min-width: fit-content;
}

.legend {
  display: flex;
  justify-content: center;
  gap: 24px;
  margin-top: 28px;
  font-size: 13px;
  color: var(--mp-text-muted);
}

.legend span {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 14px;
  height: 14px;
  border-radius: 3px;
  display: inline-block;
}

.dot.available {
  background: #fff;
  border: 1px solid #c8c9cc;
}

.dot.selected {
  background: var(--mp-primary);
}

.dot.sold {
  background: #e0e0e0;
}

.dot.couple {
  background: #fff4f0;
  border: 1px solid #ffc4b0;
}

/* ---- 侧边面板 ---- */

.panel {
  padding: 20px;
  position: sticky;
  top: 80px;
}

.panel h3 {
  margin: 0 0 16px;
  font-size: 16px;
}

.empty {
  font-size: 13px;
  padding: 20px 0;
  text-align: center;
}

.picked {
  list-style: none;
  margin: 0 0 16px;
  padding: 0;
  max-height: 240px;
  overflow-y: auto;
}

.picked li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 0;
  border-bottom: 1px solid var(--mp-divider);
  font-size: 14px;
}

/* 与画在 canvas 上的标记保持一致，列表和座位图可以对照着看。 */
.picked .face {
  font-size: 17px;
  line-height: 1;
}

.picked .label {
  flex: 1;
}

.picked .price {
  color: var(--mp-primary);
  font-weight: 600;
}

.total {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 12px 0 16px;
  border-top: 1px solid var(--mp-border);
}

.total .amount {
  font-size: 22px;
  font-weight: 700;
  color: var(--mp-primary);
}

.confirm {
  width: 100%;
}

.tip {
  font-size: 12px;
  margin: 12px 0 0;
  line-height: 1.6;
}
</style>
