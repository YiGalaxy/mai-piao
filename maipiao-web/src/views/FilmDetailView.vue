<template>
  <div class="film-detail">
    <el-skeleton v-if="loadingFilm" :rows="6" animated style="padding: 40px 16px" />

    <template v-else-if="film">
      <!--
        深色色带。色相由影片自己的 id 推导出来，所以每部片子的页面都有自己的
        颜色，海报是坐在自己的色调上，而不是坐在中性灰上。
      -->
      <div class="hero" :style="heroStyle">
        <div class="mp-container hero-inner">
          <div class="poster" :style="posterStyle(film)">
            <img
              v-if="film.posterUrl && posterUsable"
              :src="film.posterUrl"
              :alt="film.title"
              @error="posterUsable = false"
            />
            <span v-else class="poster-fallback">{{ film.title.slice(0, 2) }}</span>
          </div>

          <div class="hero-info">
            <h1>{{ film.title }}</h1>
            <p class="en-name">{{ film.enTitle }}</p>

            <div class="meta-line">
              <span>{{ film.tags }}</span>
              <el-divider direction="vertical" />
              <span>{{ film.duration }}分钟</span>
              <el-divider direction="vertical" />
              <span>{{ film.showDate }} 上映</span>
            </div>

            <div class="meta-line">导演：{{ film.director }}</div>
            <div class="meta-line">主演：{{ film.actors }}</div>

            <div class="score-row">
              <template v-if="film.score > 0">
                <span class="score-num">{{ film.score.toFixed(1) }}</span>
                <span class="score-label">分</span>
              </template>
              <span v-else class="score-none">暂无评分</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 场次列表 -->
      <div class="mp-container">
        <div class="date-bar">
          <span class="date-label">选择日期</span>
          <div class="date-tabs">
            <button
              v-for="day in dateOptions"
              :key="day.value"
              class="date-tab"
              :class="{ active: day.value === selectedDate }"
              @click="selectDate(day.value)"
            >
              <span class="dow">{{ day.weekday }}</span>
              <span class="dom">{{ day.label }}</span>
            </button>
          </div>
        </div>

        <el-skeleton v-if="loadingSchedules" :rows="5" animated />

        <el-empty v-else-if="groupedSchedules.length === 0" :description="`该日期暂无${scheduleWord}，换个日期试试`" />

        <div v-else class="cinema-groups">
          <div v-for="group in groupedSchedules" :key="group.venueId" class="cinema-group mp-card">
            <div class="cinema-head">
              <h3>{{ group.venueName }}</h3>
            </div>

            <div class="show-list">
              <div v-for="show in group.shows" :key="show.id" class="show-item">
                <div class="show-time">{{ formatTime(show.startTime) }}</div>
                <div class="show-hall mp-muted">{{ show.placeName }} · {{ show.placeType }}</div>
                <div class="show-price">
                  <span class="price">¥{{ show.price }}</span>
                </div>
                <div class="show-remain" :class="{ soldout: show.remainingSeat <= 0 }">
                  <template v-if="show.remainingSeat > 0">余 {{ show.remainingSeat }} 座</template>
                  <template v-else>已售罄</template>
                </div>
                <el-button
                  type="primary"
                  size="small"
                  :disabled="show.remainingSeat <= 0"
                  @click="goBuy(show)"
                >
                  {{ actionLabel(show) }}
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <el-empty v-else :description="`${subjectWord}不存在`" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchFilmDetail, fetchSchedules } from '../api/movie'
import { scheduleOf, subjectOf } from '../utils/eventTerms'

const route = useRoute()
const router = useRouter()

const film = ref(null)
const schedules = ref([])
const loadingFilm = ref(true)
const loadingSchedules = ref(true)
const posterUsable = ref(true)
const selectedDate = ref('')

const filmId = route.params.id

/**
 * 详情页同时服务电影和演出。
 *
 * 用词跟着分类走，而不是默认就是电影：演唱会没有「排片」，把它的场次叫成
 * 排片，正是那种让人一眼看出没人认真看过的细节。
 */
const subjectWord = computed(() => subjectOf(film.value?.category))
const scheduleWord = computed(() => scheduleOf(film.value?.category))

/** 每部片子是固定的，所以两次访问之间色调不会变。 */
const hue = computed(() => ((Number(filmId) || 1) * 47) % 360)

const heroStyle = computed(() => ({
  background: `linear-gradient(135deg,
    hsl(${hue.value} 42% 26%) 0%,
    hsl(${(hue.value + 35) % 360} 48% 15%) 100%)`
}))

const dateOptions = computed(() => {
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const options = []
  const today = new Date()

  for (let i = 0; i < 7; i++) {
    const d = new Date(today)
    d.setDate(today.getDate() + i)
    options.push({
      value: toIsoDate(d),
      weekday: i === 0 ? '今天' : i === 1 ? '明天' : weekdays[d.getDay()],
      label: `${d.getMonth() + 1}月${d.getDate()}日`
    })
  }
  return options
})

/**
 * 按场馆分组后的场次。
 *
 * 字段是 venueId。这里曾经读的是 cinemaId，而 API 从来没返回过这个字段 ——
 * 于是每一场拿到的 key 都是 undefined，所有场次全挤进同一个没有名字的分组里。
 * 只有在需要区分一个以上场馆的时候，这个分组 bug 才暴露出来；在那之前，
 * 正确的分组结果本来也就是一个分组。
 */
const groupedSchedules = computed(() => {
  const groups = new Map()
  for (const show of schedules.value) {
    if (!groups.has(show.venueId)) {
      groups.set(show.venueId, {
        venueId: show.venueId,
        venueName: show.venueName,
        shows: []
      })
    }
    groups.get(show.venueId).shows.push(show)
  }
  return [...groups.values()]
})

onMounted(async () => {
  selectedDate.value = dateOptions.value[0].value

  try {
    film.value = await fetchFilmDetail(filmId)
  } catch {
    film.value = null
  } finally {
    loadingFilm.value = false
  }

  loadSchedules()
})

function selectDate(date) {
  selectedDate.value = date
  loadSchedules()
}

async function loadSchedules() {
  loadingSchedules.value = true
  try {
    schedules.value = (await fetchSchedules({ filmId, showDate: selectedDate.value })) || []
  } catch {
    schedules.value = []
  } finally {
    loadingSchedules.value = false
  }
}

/**
 * 场次的购买按钮指向哪里。
 *
 * 三个去处，因为卖座位有三种方式，而路由必须在页面加载之前就定下来 ——
 * 座位图和票档选择器没有任何共享状态，拉的东西也不一样。
 *
 * 判断顺序有讲究：抢票场次不管用哪种选座方式，都得先排队，所以要放在
 * seatMode 前面判。只有座位图要求买家已经有账号；票档选择器其实也要求，
 * 但排队才是那个会消耗队里名额的动作，登录之前就去排队等于白白浪费掉它。
 */
function goBuy(show) {
  if (show.rushMode === 1) {
    router.push(`/schedules/${show.id}/queue`)
  } else if (show.seatMode === 1) {
    router.push(`/schedules/${show.id}/tickets`)
  } else {
    router.push(`/schedules/${show.id}/seats`)
  }
}

/** 按钮说的是下一个页面会做什么，而这三者并不一样。 */
function actionLabel(show) {
  if (show.remainingSeat <= 0) return '已售罄'
  if (show.rushMode === 1) return '立即抢票'
  if (show.seatMode === 1) return '立即购买'
  return '选座购票'
}

function formatTime(value) {
  return String(value).slice(11, 16)
}

function toIsoDate(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function posterStyle(f) {
  const h = (f.id * 47) % 360
  return {
    background: `linear-gradient(135deg, hsl(${h} 58% 46%), hsl(${(h + 40) % 360} 62% 30%))`
  }
}
</script>

<style scoped>
.film-detail {
  padding-bottom: 48px;
}

/* ---- 深色主视觉区 ---- */

.hero {
  padding: 40px 0;
  margin-bottom: 24px;
}

.hero-inner {
  display: flex;
  gap: 32px;
  align-items: flex-start;
}

.poster {
  width: 210px;
  height: 315px;
  flex-shrink: 0;
  border-radius: var(--mp-radius);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.4);
}

.poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.poster-fallback {
  color: #fff;
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 6px;
}

.hero-info {
  flex: 1;
  min-width: 0;
  color: #fff;
}

.hero-info h1 {
  margin: 0 0 8px;
  font-size: 32px;
  color: #fff;
}

.en-name {
  margin: 0 0 20px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.65);
}

.meta-line {
  margin-bottom: 10px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.88);
}

.meta-line :deep(.el-divider--vertical) {
  border-color: rgba(255, 255, 255, 0.3);
}

.score-row {
  margin-top: 22px;
}

.score-num {
  font-size: 42px;
  font-weight: 700;
  color: var(--mp-primary);
  line-height: 1;
}

.score-label {
  margin-left: 4px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
}

.score-none {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.6);
}

/* ---- 日期选择 ---- */

.date-bar {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 16px;
}

.date-label {
  color: var(--mp-text-muted);
  font-size: 13px;
  flex-shrink: 0;
}

.date-tabs {
  display: flex;
  gap: 8px;
}

.date-tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 9px 20px;
  border: 1px solid var(--mp-border);
  background: #fff;
  border-radius: var(--mp-radius);
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  color: var(--mp-text);
  transition:
    border-color 0.18s,
    background 0.18s,
    color 0.18s;
}

.date-tab:hover {
  border-color: var(--mp-primary);
  color: var(--mp-primary);
}

.date-tab.active {
  background: var(--mp-primary);
  border-color: var(--mp-primary);
  color: #fff;
  box-shadow: var(--mp-shadow-button);
}

.dow {
  font-weight: 600;
}

.dom {
  font-size: 12px;
  opacity: 0.85;
}

/* ---- 场次列表 ---- */

.cinema-group {
  margin-bottom: 16px;
  padding: 0 20px 8px;
}

.cinema-head h3 {
  margin: 0;
  padding: 18px 0 14px;
  font-size: 17px;
  color: var(--mp-text-strong);
}

.show-item {
  display: grid;
  grid-template-columns: 84px 1fr 100px 110px 110px;
  align-items: center;
  gap: 16px;
  padding: 16px 0;
  /* 场次之间用一道浅灰线隔开，和这个品类一样：靠线分行，而不是给每行画框。 */
  border-top: 1px solid var(--mp-divider);
}

.show-time {
  font-size: 19px;
  font-weight: 600;
  color: var(--mp-text-strong);
}

.price {
  color: var(--mp-primary);
  font-size: 18px;
  font-weight: 600;
}

.show-remain {
  font-size: 13px;
  color: var(--mp-text-muted);
}

.show-remain.soldout {
  color: #ccc;
}
</style>
