<template>
  <div class="home page-with-tabbar">
    <van-nav-bar title="正在热映" fixed placeholder />

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <van-skeleton v-if="initialLoading" title :row="3" style="padding: 16px" />

      <template v-else>
        <van-empty
          v-if="films.length === 0"
          image="search"
          description="暂无正在热映的影片"
        />

        <div v-else class="film-list">
          <div
            v-for="film in films"
            :key="film.id"
            class="film-card"
            @click="goDetail(film)"
          >
            <!--
              The seed data points at /img/poster/*.jpg, which this project
              does not ship. Rather than link out to a placeholder service,
              the poster falls back to a colour block derived from the film
              name, so the list still looks composed offline.
            -->
            <div class="poster" :style="posterStyle(film)">
              <img
                v-if="film.posterUrl && isPosterUsable(film.id)"
                :src="film.posterUrl"
                :alt="film.name"
                @error="markPosterBroken(film.id)"
              />
              <span v-else class="poster-fallback">{{ film.name.slice(0, 2) }}</span>
            </div>

            <div class="info">
              <div class="title-row">
                <h3>{{ film.name }}</h3>
                <span v-if="film.score > 0" class="score">{{ film.score.toFixed(1) }}</span>
                <span v-else class="score score-none">暂无评分</span>
              </div>
              <p class="mp-muted">{{ film.enName }}</p>
              <p class="mp-muted">{{ film.filmType }} · {{ film.duration }}分钟</p>
              <p class="mp-muted">导演：{{ film.director }}</p>
            </div>
          </div>
        </div>
      </template>
    </van-pull-refresh>

    <van-tabbar route>
      <van-tabbar-item to="/" icon="video-o">热映</van-tabbar-item>
      <van-tabbar-item to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchFilms } from '../api/movie'

defineOptions({ name: 'HomeView' })

const router = useRouter()

const films = ref([])
const initialLoading = ref(true)
const refreshing = ref(false)
const imageOk = ref({})

// Status 1 = now showing.
const NOW_SHOWING = 1

onMounted(loadFilms)

async function loadFilms() {
  try {
    films.value = await fetchFilms({ status: NOW_SHOWING })
  } catch {
    // request.js already showed a message; leave the list empty so the page
    // renders its empty state instead of a broken one.
    films.value = []
  } finally {
    initialLoading.value = false
    refreshing.value = false
  }
}

function onRefresh() {
  refreshing.value = true
  loadFilms()
}

function goDetail(film) {
  // Detail route belongs to the schedule flow, which lands with movie-service.
  // Until then, keep the tap responsive rather than doing nothing.
  router.push({ name: 'home', query: { filmId: film.id } })
}

/**
 * Posters are assumed usable until the browser tells us otherwise.
 *
 * The set records failures only. The first version of this seeded the set from
 * inside the :style binding, which mutated reactive state during render and
 * re-triggered the render - an infinite loop. Nothing that reads reactive state
 * may also write it.
 */
function isPosterUsable(filmId) {
  return imageOk.value[filmId] !== false
}

function markPosterBroken(filmId) {
  imageOk.value = { ...imageOk.value, [filmId]: false }
}

/** Deterministic colour per film, so a poster never changes between renders. */
function posterStyle(film) {
  const hue = (film.id * 47) % 360
  return {
    background: `linear-gradient(135deg, hsl(${hue} 62% 58%), hsl(${(hue + 40) % 360} 58% 42%))`
  }
}
</script>

<style scoped>
.film-list {
  padding: 8px 0;
}

.film-card {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  margin-bottom: 8px;
  cursor: pointer;
}

.film-card:active {
  background: #fafafa;
}

.poster {
  width: 76px;
  height: 106px;
  border-radius: 6px;
  flex-shrink: 0;
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
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 2px;
}

.info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info p {
  margin: 0;
}

.title-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.title-row h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.score {
  margin-left: auto;
  flex-shrink: 0;
  color: var(--van-primary-color);
  font-weight: 600;
  font-size: 15px;
}

.score-none {
  color: var(--mp-text-muted);
  font-weight: 400;
  font-size: 13px;
}
</style>
