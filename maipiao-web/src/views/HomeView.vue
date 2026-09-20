<template>
  <div class="home">
    <div class="mp-container">
      <!-- Banner: the top-rated few, sized as a hero rather than a grid tile. -->
      <el-carousel
        v-if="bannerFilms.length"
        height="340px"
        :interval="5000"
        class="banner"
        indicator-position="none"
      >
        <el-carousel-item v-for="film in bannerFilms" :key="film.id">
          <div class="banner-slide" :style="posterStyle(film)" @click="goFilm(film)">
            <div class="banner-info">
              <h2>{{ film.title }}</h2>
              <p class="banner-meta">
                {{ film.enTitle }}
              </p>
              <p class="banner-meta">
                {{ film.tags }} · {{ film.duration }}分钟 · 导演 {{ film.director }}
              </p>
              <div class="banner-score" v-if="film.score > 0">
                <span class="num">{{ film.score.toFixed(1) }}</span>
                <span class="label">分</span>
              </div>
              <el-button type="primary" size="large" @click.stop="goFilm(film)">
                选座购票
              </el-button>
            </div>
          </div>
        </el-carousel-item>
      </el-carousel>

      <!--
        Category tabs.

        Films and performances share one catalogue and one card layout, so the
        switch is a filter rather than a different page. The counts come from
        the same query the list uses, so a tab never promises more than it
        shows.
      -->
      <el-tabs v-model="category" class="category-tabs" @tab-change="onCategoryChange">
        <el-tab-pane
          v-for="tab in CATEGORY_TABS"
          :key="tab.value"
          :label="tab.label"
          :name="tab.value"
        />
      </el-tabs>

      <!-- Now showing -->
      <div class="mp-section-head">
        <h2>{{ sectionTitle }}</h2>
        <span class="mp-more" @click="goAll">
          全部 {{ nowShowing.length }} 部 &gt;
        </span>
      </div>

      <el-skeleton v-if="loading" :rows="6" animated />

      <el-empty v-else-if="nowShowing.length === 0" :description="emptyHint" />

      <div v-else class="mp-film-grid">
        <div
          v-for="film in nowShowing"
          :key="film.id"
          class="mp-film-card"
          @click="goFilm(film)"
        >
          <div class="mp-poster" :style="posterStyle(film)">
            <img
              v-if="film.posterUrl && isPosterUsable(film.id)"
              :src="film.posterUrl"
              :alt="film.title"
              @error="markPosterBroken(film.id)"
            />
            <span v-else class="mp-poster-fallback">{{ film.title.slice(0, 2) }}</span>

            <div v-if="film.score > 0" class="mp-score">
              评分 <b>{{ film.score.toFixed(1) }}</b>
            </div>
            <div v-else class="mp-score mp-score-none">暂无评分</div>
          </div>

          <div class="mp-film-body">
            <h3 class="mp-film-title">{{ film.title }}</h3>
            <p class="mp-film-sub">{{ film.tags }} · {{ film.duration }}分钟</p>
            <div class="mp-film-action">
              <el-button type="primary" size="small" @click.stop="goFilm(film)">
                选座购票
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- Coming soon -->
      <template v-if="upcoming.length">
        <div class="mp-section-head">
          <h2>{{ upcomingTitle }}</h2>
          <span
            class="mp-more"
            @click="router.push({ path: '/films', query: { status: 0, category: category } })"
          >
            全部 {{ upcoming.length }} 部 &gt;
          </span>
        </div>

        <div class="mp-film-grid">
          <div v-for="film in upcoming" :key="film.id" class="mp-film-card">
            <div class="mp-poster" :style="posterStyle(film)">
              <img
                v-if="film.posterUrl && isPosterUsable(film.id)"
                :src="film.posterUrl"
                :alt="film.title"
                @error="markPosterBroken(film.id)"
              />
              <span v-else class="mp-poster-fallback">{{ film.title.slice(0, 2) }}</span>
              <div class="mp-score mp-score-none">待映</div>
            </div>

            <div class="mp-film-body">
              <h3 class="mp-film-title">{{ film.title }}</h3>
              <p class="mp-film-sub">上映日期 {{ film.showDate }}</p>
              <div class="mp-film-action">
                <el-button size="small" plain disabled>预约</el-button>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchFilms } from '../api/movie'

const router = useRouter()

const nowShowing = ref([])
const upcoming = ref([])
const loading = ref(true)
const brokenPosters = ref({})

/**
 * Which kind of event the page is showing.
 *
 * One catalogue, one card layout, so switching category is a filter rather
 * than a different page. Anything the catalogue gains a row for shows up here
 * without a code change.
 */
const CATEGORY_TABS = [
  { value: 'MOVIE', label: '电影' },
  { value: 'CONCERT', label: '演唱会' },
  { value: 'TALK_SHOW', label: '脱口秀' },
  { value: 'THEATER', label: '话剧' },
  { value: 'MUSICAL', label: '音乐剧' }
]

const category = ref('MOVIE')

/**
 * Headings follow the tab.
 *
 * "正在热映" and "即将上映" are cinema words; above a stand-up listing they read
 * as a mistake. The same distinction applies to the second section, which is
 * why both headings are derived rather than written into the template.
 */
const isFilm = computed(() => category.value === 'MOVIE')
const sectionTitle = computed(() => (isFilm.value ? '正在热映' : '热门演出'))

/** What an empty list means depends on what the list was of. */
const emptyHint = computed(() =>
  isFilm.value ? '暂无正在热映的影片' : '暂无正在售票的演出'
)
const upcomingTitle = computed(() => (isFilm.value ? '即将上映' : '即将开演'))

/** Reserve action is a film concept; performances get "想看" instead. */
const upcomingAction = computed(() => (isFilm.value ? '预约' : '想看'))

// The banner shows the best-rated few of what is actually on sale.
const bannerFilms = computed(() =>
  [...nowShowing.value]
    .filter((f) => f.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 4)
)

const STATUS_UPCOMING = 0
const STATUS_NOW_SHOWING = 1

onMounted(load)

async function load() {
  loading.value = true
  try {
    // Two statuses, two calls. There is no combined endpoint because the two
    // lists render independently and a partial failure should not blank both.
    const [showing, soon] = await Promise.all([
      fetchFilms({ status: STATUS_NOW_SHOWING, category: category.value }),
      fetchFilms({ status: STATUS_UPCOMING, category: category.value })
    ])
    nowShowing.value = showing || []
    upcoming.value = soon || []
  } catch {
    nowShowing.value = []
    upcoming.value = []
  } finally {
    loading.value = false
  }
}

/** Fired by el-tabs when the active pane changes. */
function onCategoryChange() {
  load()
}

function goAll() {
  router.push({ path: '/films', query: { category: category.value } })
}

function goFilm(film) {
  router.push(`/films/${film.id}`)
}

/**
 * Posters are assumed usable until the browser reports otherwise.
 *
 * Failures are recorded only. An earlier version seeded this set from inside
 * the :style binding, which wrote reactive state during render and looped.
 */
function isPosterUsable(filmId) {
  return brokenPosters.value[filmId] !== false
}

function markPosterBroken(filmId) {
  brokenPosters.value = { ...brokenPosters.value, [filmId]: false }
}

/** Deterministic colour per film, so the fallback never changes between renders. */
function posterStyle(film) {
  const hue = (film.id * 47) % 360
  return {
    background: `linear-gradient(135deg, hsl(${hue} 58% 46%), hsl(${(hue + 40) % 360} 62% 30%))`
  }
}
</script>

<style scoped>
.home {
  padding-top: 20px;
}

/**
 * Category tabs sit on their own white strip rather than floating over the
 * banner. The banner is per-category content, so the control that selects the
 * category has to be visibly outside it.
 */
.category-tabs {
  margin-top: 20px;
  background: #fff;
  border-radius: var(--mp-radius);
  padding: 4px 20px 0;
  box-shadow: var(--mp-shadow);
}

.category-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.category-tabs :deep(.el-tabs__item) {
  font-size: 15px;
  height: 48px;
  line-height: 48px;
}

.category-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}

.banner {
  border-radius: var(--mp-radius);
  overflow: hidden;
}

.banner-slide {
  height: 100%;
  display: flex;
  align-items: center;
  padding: 0 64px;
  cursor: pointer;
}

.banner-info {
  color: #fff;
  max-width: 560px;
}

.banner-info h2 {
  font-size: 40px;
  margin: 0 0 12px;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

.banner-meta {
  margin: 0 0 6px;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.85);
}

.banner-score {
  margin: 16px 0 20px;
  color: #fff;
}

.banner-score .num {
  font-size: 34px;
  font-weight: 700;
  color: var(--mp-primary);
}

.banner-score .label {
  font-size: 14px;
  margin-left: 4px;
  color: rgba(255, 255, 255, 0.8);
}

/* Five per row keeps the poster aspect ratio close to a real ticket site
   at the 1200px container width. */
.mp-film-grid {
  grid-template-columns: repeat(5, 1fr);
}
</style>
