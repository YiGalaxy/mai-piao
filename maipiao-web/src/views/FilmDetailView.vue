<template>
  <div class="mp-container film-detail">
    <el-skeleton v-if="loadingFilm" :rows="6" animated />

    <template v-else-if="film">
      <!-- Film header -->
      <div class="film-head mp-card">
        <div class="poster" :style="posterStyle(film)">
          <img
            v-if="film.posterUrl && posterUsable"
            :src="film.posterUrl"
            :alt="film.name"
            @error="posterUsable = false"
          />
          <span v-else class="poster-fallback">{{ film.name.slice(0, 2) }}</span>
        </div>

        <div class="head-info">
          <h1>{{ film.name }}</h1>
          <p class="en-name mp-muted">{{ film.enName }}</p>

          <div class="meta-line">
            <span>{{ film.filmType }}</span>
            <el-divider direction="vertical" />
            <span>{{ film.duration }}分钟</span>
            <el-divider direction="vertical" />
            <span>{{ film.releaseDate }} 上映</span>
          </div>

          <div class="meta-line">
            <span class="mp-muted">导演：</span>{{ film.director }}
          </div>
          <div class="meta-line">
            <span class="mp-muted">主演：</span>{{ film.actors }}
          </div>

          <div v-if="film.score > 0" class="score-block">
            <span class="score-num">{{ film.score.toFixed(1) }}</span>
            <span class="score-label mp-muted">分</span>
          </div>
          <div v-else class="score-block mp-muted">暂无评分</div>
        </div>
      </div>

      <!-- Date picker -->
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

      <!-- Screenings grouped by cinema -->
      <el-skeleton v-if="loadingSchedules" :rows="5" animated />

      <el-empty
        v-else-if="groupedSchedules.length === 0"
        description="该日期暂无排片，换个日期试试"
      />

      <div v-else class="cinema-groups">
        <div v-for="group in groupedSchedules" :key="group.cinemaId" class="cinema-group mp-card">
          <div class="cinema-head">
            <h3>{{ group.cinemaName }}</h3>
          </div>

          <div class="show-list">
            <div v-for="show in group.shows" :key="show.id" class="show-item">
              <div class="show-time">{{ formatTime(show.startTime) }}</div>
              <div class="show-hall mp-muted">
                {{ show.hallName }} · {{ show.hallType }}
              </div>
              <div class="show-price">
                <span class="price">¥{{ show.price }}</span>
              </div>
              <div class="show-remain mp-muted">
                <template v-if="show.remainingSeat > 0">余 {{ show.remainingSeat }} 座</template>
                <template v-else>已售罄</template>
              </div>
              <el-button
                type="primary"
                size="small"
                :disabled="show.remainingSeat <= 0"
                @click="goSeatSelect(show)"
              >
                {{ show.remainingSeat > 0 ? '选座购票' : '已售罄' }}
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <el-empty v-else description="影片不存在" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchFilmDetail, fetchSchedules } from '../api/movie'

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
 * Seven days starting today, matching the range the demo data generator
 * produces. Labels are computed locally rather than fetched, so the bar
 * renders before any request completes.
 */
const dateOptions = computed(() => {
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const options = []
  const today = new Date()

  for (let i = 0; i < 7; i++) {
    const d = new Date(today)
    d.setDate(today.getDate() + i)
    const value = toIsoDate(d)
    options.push({
      value,
      weekday: i === 0 ? '今天' : i === 1 ? '明天' : weekdays[d.getDay()],
      label: `${d.getMonth() + 1}月${d.getDate()}日`
    })
  }
  return options
})

/** Groups screenings by cinema, preserving the server's time ordering. */
const groupedSchedules = computed(() => {
  const groups = new Map()
  for (const show of schedules.value) {
    if (!groups.has(show.cinemaId)) {
      groups.set(show.cinemaId, {
        cinemaId: show.cinemaId,
        cinemaName: show.cinemaName,
        shows: []
      })
    }
    groups.get(show.cinemaId).shows.push(show)
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

function goSeatSelect(show) {
  router.push(`/schedules/${show.id}/seats`)
}

function formatTime(value) {
  // Backend sends "2026-09-20 19:30:00"; only the clock part is shown.
  return String(value).slice(11, 16)
}

function toIsoDate(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function posterStyle(f) {
  const hue = (f.id * 47) % 360
  return {
    background: `linear-gradient(135deg, hsl(${hue} 58% 46%), hsl(${(hue + 40) % 360} 62% 30%))`
  }
}
</script>

<style scoped>
.film-detail {
  padding-top: 20px;
}

.film-head {
  display: flex;
  gap: 28px;
  padding: 24px;
}

.poster {
  width: 200px;
  height: 300px;
  flex-shrink: 0;
  border-radius: var(--mp-radius);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.poster img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.poster-fallback {
  color: #fff;
  font-size: 36px;
  font-weight: 700;
  letter-spacing: 6px;
}

.head-info {
  flex: 1;
  min-width: 0;
}

.head-info h1 {
  margin: 0 0 6px;
  font-size: 28px;
  color: var(--mp-text-strong);
}

.en-name {
  margin: 0 0 18px;
  font-size: 14px;
}

.meta-line {
  margin-bottom: 10px;
  font-size: 14px;
}

.score-block {
  margin-top: 20px;
}

.score-num {
  font-size: 36px;
  font-weight: 700;
  color: var(--mp-primary);
}

.score-label {
  margin-left: 4px;
}

/* ---- date bar ---- */

.date-bar {
  display: flex;
  align-items: center;
  gap: 20px;
  margin: 24px 0 16px;
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
  padding: 8px 18px;
  border: 1px solid var(--mp-border);
  background: #fff;
  border-radius: var(--mp-radius);
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  color: var(--mp-text);
  transition: all 0.15s;
}

.date-tab:hover {
  border-color: var(--mp-primary);
}

.date-tab.active {
  background: var(--mp-primary);
  border-color: var(--mp-primary);
  color: #fff;
}

.dow {
  font-weight: 600;
}

.dom {
  font-size: 12px;
  opacity: 0.85;
}

/* ---- screenings ---- */

.cinema-group {
  margin-bottom: 16px;
  padding: 0 20px 8px;
}

.cinema-head h3 {
  margin: 0;
  padding: 16px 0 12px;
  font-size: 17px;
  border-bottom: 1px solid var(--mp-border);
  color: var(--mp-text-strong);
}

.show-item {
  display: grid;
  grid-template-columns: 80px 1fr 100px 110px 110px;
  align-items: center;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid #f0f0f0;
}

.show-item:last-child {
  border-bottom: none;
}

.show-time {
  font-size: 18px;
  font-weight: 600;
  color: var(--mp-text-strong);
}

.price {
  color: var(--mp-primary);
  font-size: 17px;
  font-weight: 600;
}

.show-remain {
  font-size: 13px;
}
</style>
