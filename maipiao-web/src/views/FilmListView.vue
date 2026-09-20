<template>
  <div class="mp-container film-list-page">
    <div class="filter-bar mp-card">
      <div class="filter-row">
        <span class="filter-label">状态</span>
        <el-radio-group v-model="status" @change="reload">
          <el-radio-button :value="1">正在热映</el-radio-button>
          <el-radio-button :value="0">即将上映</el-radio-button>
          <el-radio-button :value="null">全部</el-radio-button>
        </el-radio-group>
      </div>

      <div v-if="keyword" class="filter-row">
        <span class="filter-label">搜索</span>
        <el-tag closable @close="clearKeyword">“{{ keyword }}”</el-tag>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="8" animated />

    <el-empty v-else-if="films.length === 0" description="没有符合条件的影片" />

    <div v-else class="mp-film-grid">
      <div
        v-for="film in films"
        :key="film.id"
        class="mp-film-card"
        @click="router.push(`/films/${film.id}`)"
      >
        <div class="mp-poster" :style="posterStyle(film)">
          <img
            v-if="film.posterUrl && isPosterUsable(film.id)"
            :src="film.posterUrl"
            :alt="film.title"
            @error="markPosterBroken(film.id)"
          />
          <span v-else class="mp-poster-fallback">{{ film.title.slice(0, 2) }}</span>

          <div v-if="film.status === 1 && film.score > 0" class="mp-score">
            评分 <b>{{ film.score.toFixed(1) }}</b>
          </div>
          <div v-else class="mp-score mp-score-none">
            {{ film.status === 0 ? `待映 ${film.showDate}` : '暂无评分' }}
          </div>
        </div>

        <div class="mp-film-body">
          <h3 class="mp-film-title">{{ film.title }}</h3>
          <p class="mp-film-sub">{{ film.tags }} · {{ film.duration }}分钟</p>
          <p class="mp-film-sub">导演 {{ film.director }}</p>
          <div class="mp-film-action">
            <el-button
              v-if="film.status === 1"
              type="primary"
              size="small"
              @click.stop="router.push(`/films/${film.id}`)"
            >
              选座购票
            </el-button>
            <el-button v-else size="small" plain disabled>暂未开售</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchFilms } from '../api/movie'

const router = useRouter()
const route = useRoute()

const films = ref([])
const loading = ref(true)
const status = ref(1)
const keyword = ref('')

onMounted(() => {
  // Support deep links like /films?status=0 from the home page's "全部" link.
  if (route.query.status !== undefined) {
    const parsed = Number(route.query.status)
    status.value = Number.isNaN(parsed) ? null : parsed
  }
  if (typeof route.query.q === 'string') {
    keyword.value = route.query.q
  }
  reload()
})

watch(
  () => route.query.q,
  (value) => {
    keyword.value = typeof value === 'string' ? value : ''
  }
)

async function reload() {
  loading.value = true
  try {
    const list = await fetchFilms(status.value === null ? {} : { status: status.value })
    films.value = filterByKeyword(list || [])
  } catch {
    films.value = []
  } finally {
    loading.value = false
  }
}

/**
 * Filtering happens on the client because the catalogue is small and the
 * search box is a convenience, not a feature. If the catalogue grows, this
 * becomes a server-side query with an index behind it.
 */
function filterByKeyword(list) {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) {
    return list
  }
  return list.filter(
    (f) =>
      f.title.toLowerCase().includes(needle) ||
      (f.enTitle || '').toLowerCase().includes(needle) ||
      (f.director || '').toLowerCase().includes(needle)
  )
}

function clearKeyword() {
  keyword.value = ''
  router.replace({ query: {} })
  reload()
}

function isPosterUsable(filmId) {
  return brokenPosters.value[filmId] !== false
}

const brokenPosters = ref({})

function markPosterBroken(filmId) {
  brokenPosters.value = { ...brokenPosters.value, [filmId]: false }
}

function posterStyle(film) {
  const hue = (film.id * 47) % 360
  return {
    background: `linear-gradient(135deg, hsl(${hue} 58% 46%), hsl(${(hue + 40) % 360} 62% 30%))`
  }
}
</script>

<style scoped>
.film-list-page {
  padding-top: 20px;
}

.filter-bar {
  padding: 16px 20px;
  margin-bottom: 20px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.filter-row + .filter-row {
  margin-top: 12px;
}

.filter-label {
  color: var(--mp-text-muted);
  font-size: 13px;
  flex-shrink: 0;
}
</style>
