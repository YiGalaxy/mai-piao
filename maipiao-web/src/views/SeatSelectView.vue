<template>
  <div class="mp-container seat-page">
    <el-skeleton v-if="loading" :rows="8" animated />

    <template v-else-if="seatMap">
      <!-- Screening summary -->
      <div class="mp-card summary">
        <div>
          <h2>{{ seatMap.filmName }}</h2>
          <p class="mp-muted">
            {{ seatMap.cinemaName }} · {{ seatMap.hallName }}（{{ seatMap.hallType }}）
          </p>
        </div>
        <div class="start-time">
          <div class="date">{{ formatDate(seatMap.startTime) }}</div>
          <div class="time">{{ formatTime(seatMap.startTime) }}</div>
        </div>
      </div>

      <div class="seat-layout">
        <!-- Seat map -->
        <div class="mp-card map-panel">
          <div class="screen">
            <div class="screen-bar"></div>
            <span class="screen-label">银幕中央</span>
          </div>

          <div class="canvas-wrap">
            <canvas ref="canvasRef" :width="canvasWidth" :height="canvasHeight" @click="onCanvasClick"></canvas>
          </div>

          <div class="legend">
            <span><i class="dot available"></i>可选</span>
            <span><i class="dot selected"></i>已选</span>
            <span><i class="dot sold"></i>已售</span>
            <span><i class="dot couple"></i>情侣座</span>
          </div>
        </div>

        <!-- Selection panel -->
        <aside class="mp-card panel">
          <h3>已选座位</h3>

          <div v-if="selectedSeats.length === 0" class="empty mp-muted">
            请在左侧座位图上选择座位
          </div>

          <ul v-else class="picked">
            <li v-for="(seat, index) in selectedSeats" :key="seat.seatIndex">
              <span class="face">{{ SEAT_FACES[index % SEAT_FACES.length] }}</span>
              <span class="label">{{ labelOf(seat) }}</span>
              <span class="price">¥{{ seatMap.price }}</span>
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

// Geometry. Sized so a 14x16 IMAX hall fits without scrolling.
const SEAT_W = 30
const SEAT_H = 26
const GAP = 6
const OFFSET_X = 44
const OFFSET_Y = 20
const MAX_SELECT = 6

/** One per selected seat, in selection order. */
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

const totalAmount = computed(() => {
  const price = Number(seatMap.value?.price || 0)
  return (price * selected.value.length).toFixed(2)
})

onMounted(async () => {
  try {
    seatMap.value = await fetchSeatMap(scheduleId)
    normaliseSeatIndexes()
    // The canvas is only in the DOM after seatMap resolves, hence nextTick-free
    // direct draw here: the template has already re-rendered by this point.
    requestAnimationFrame(draw)
  } catch {
    seatMap.value = null
  } finally {
    loading.value = false
  }
})

/**
 * Converts seatIndex back to a number.
 *
 * <p>The backend serialises every Long as a JSON string, because a 19-digit
 * snowflake id does not survive JavaScript's double-precision parse. seatIndex
 * is small enough to be safe either way, but it arrives as a string all the
 * same - and a string index compared against a number index silently matches
 * nothing, which shows up as selections that will not toggle.
 */
function normaliseSeatIndexes() {
  if (!seatMap.value?.seats) return
  seatMap.value.seats = seatMap.value.seats.map((seat) => ({
    ...seat,
    seatIndex: Number(seat.seatIndex)
  }))
}

/**
 * Draws the hall.
 *
 * Position comes from the seat's physical row and column, not from its
 * seat_index - so aisle columns, which have no seats, simply leave a gap
 * without any special-casing.
 */
function draw() {
  const canvas = canvasRef.value
  if (!canvas || !seatMap.value) return

  const ctx = canvas.getContext('2d')
  const dpr = window.devicePixelRatio || 1

  // Render at device resolution so the seat edges are crisp on a HiDPI screen.
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

    // Couple seats are drawn slightly wider so the pairing is visible.
    const w = seat.type === 1 ? SEAT_W + GAP - 2 : SEAT_W

    if (seat.status === 1) {
      // Taken seats are filled with the page background colour, so they read
      // as "not part of the map" rather than as a different kind of choice.
      ctx.fillStyle = '#f0f0f0'
      ctx.strokeStyle = '#e8e8e8'
    } else if (selectedIndexes.has(seat.seatIndex)) {
      ctx.fillStyle = '#ff6700'
      ctx.strokeStyle = '#e05a00'
    } else if (seat.type === 1) {
      ctx.fillStyle = '#fff3ea'
      ctx.strokeStyle = '#ffc9a3'
    } else {
      ctx.fillStyle = '#ffffff'
      ctx.strokeStyle = '#c8c9cc'
    }

    roundRect(ctx, x, y, w, SEAT_H, 4)
    ctx.fill()
    ctx.lineWidth = 1
    ctx.stroke()

    // A character on each chosen seat.
    //
    // This is the one piece of pure decoration in the app, and it earns its
    // place: a wall of identical orange rectangles makes it hard to see at a
    // glance which seats are yours, and the seats that were already taken look
    // almost the same as the ones you picked. A distinct face per seat makes
    // "these are mine" instant, and it gives the screen some warmth at the
    // moment the user has just committed to spending money.
    if (selectedIndexes.has(seat.seatIndex)) {
      const picked = selected.value.findIndex((s) => s.seatIndex === seat.seatIndex)
      ctx.font = '15px "Apple Color Emoji", "Segoe UI Emoji", sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText(SEAT_FACES[picked % SEAT_FACES.length], x + w / 2, y + SEAT_H / 2 + 1)
    }
  }

  // Row numbers down the left edge, so people can find their row.
  ctx.fillStyle = '#999'
  ctx.font = '11px sans-serif'
  ctx.textAlign = 'right'
  ctx.textBaseline = 'middle'
  for (let row = 1; row <= seatMap.value.rowCount; row++) {
    const y = (row - 1) * (SEAT_H + GAP) + OFFSET_Y + SEAT_H / 2
    ctx.fillText(String(row), OFFSET_X - 10, y)
  }
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

  // Hit-testing by geometry rather than by tracking drawn rectangles: the
  // grid is uniform, so the inverse of the layout formula is enough.
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
      // Left as the string it arrived as.
      //
      // Number() here would undo the whole reason ids are serialised as
      // strings: 2101643262211633153 does not fit in a double and comes back
      // as ...200, so the server would be asked to lock seats on a screening
      // that does not exist. Jackson parses the string back to a Long on the
      // other side without any help from us.
      scheduleId,
      seatIndexes: selected.value.map((s) => s.seatIndex)
    })

    // Hand the hold to the checkout page.
    //
    // sessionStorage rather than the URL: the lock token is a credential for
    // a held resource, and a query string ends up in browser history, in
    // referrer headers, and in whatever the user pastes into a chat window.
    sessionStorage.setItem(
      'maipiao_pending_seats',
      JSON.stringify({
        lockToken: result.lockToken,
        scheduleId: String(result.scheduleId),
        expireSeconds: result.expireSeconds,
        seats: selected.value.map((s) => ({
          seatIndex: s.seatIndex,
          label: `${s.row}排${s.col}座`
        }))
      })
    )

    router.push({ name: 'checkout' })
  } catch {
    // Covers the conflict case: someone took a seat between drawing the map
    // and confirming. Reload so the user sees the current state rather than
    // the stale one they were looking at.
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
    // leave the page as-is
  }
}

function labelOf(seat) {
  return `${seat.row}排${seat.col}座`
}

function formatTime(value) {
  return String(value).slice(11, 16)
}

function formatDate(value) {
  return String(value).slice(0, 10)
}
</script>

<style scoped>
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

/* ---- side panel ---- */

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

/* Matches the marker drawn on the canvas, so the list and the map can be
   read against each other. */
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
