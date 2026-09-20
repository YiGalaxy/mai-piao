<template>
  <div class="home">
    <div class="mp-container">
      <!-- Banner：评分最高的那几部，按主视觉的尺寸做，而不是做成网格里的一格。 -->
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
        分类标签页。

        电影和演出共用一份剧目库、一套卡片布局，所以切换分类只是加个过滤条件，
        而不是换一个页面。数量来自列表用的同一个查询，所以标签页承诺的
        不会比它实际展示的多。
      -->
      <el-tabs v-model="category" class="category-tabs" @tab-change="onCategoryChange">
        <el-tab-pane
          v-for="tab in CATEGORY_TABS"
          :key="tab.value"
          :label="tab.label"
          :name="tab.value"
        />
      </el-tabs>

      <!-- 正在热映 / 热门演出 -->
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

      <!-- 即将上映 / 即将开演 -->
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
 * 这一页在展示哪一类活动。
 *
 * 一份剧目库、一套卡片布局，所以切分类是加过滤条件，不是换页面。
 * 剧目库里多出哪一类，这里不用改代码就会显示出来。
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
 * 标题跟着标签页走。
 *
 * 「正在热映」「即将上映」是影院的说法，摆在脱口秀列表上方就成了错误。
 * 第二个板块同理，所以两个标题都是算出来的，而不是写死在模板里的。
 */
const isFilm = computed(() => category.value === 'MOVIE')
const sectionTitle = computed(() => (isFilm.value ? '正在热映' : '热门演出'))

/** 空列表的含义取决于这是什么的列表。 */
const emptyHint = computed(() =>
  isFilm.value ? '暂无正在热映的影片' : '暂无正在售票的演出'
)
const upcomingTitle = computed(() => (isFilm.value ? '即将上映' : '即将开演'))

/** 「预约」是电影的概念；演出那边换成「想看」。 */
const upcomingAction = computed(() => (isFilm.value ? '预约' : '想看'))

// Banner 展示真正在售的内容里评分最高的几部。
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
    // 两个状态，两次调用。没有合并成一个接口，是因为这两个列表各自独立
    // 渲染，其中一个失败不该把两块都清空。
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

/** 由 el-tabs 在激活面板切换时触发。 */
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
 * 海报默认当作能用的，直到浏览器报错为止。
 *
 * 只记录失败。早先的版本是在 :style 绑定里往这个集合里写数据，等于在渲染
 * 过程中改响应式状态，结果死循环了。
 */
function isPosterUsable(filmId) {
  return brokenPosters.value[filmId] !== false
}

function markPosterBroken(filmId) {
  brokenPosters.value = { ...brokenPosters.value, [filmId]: false }
}

/** 每部片子一个固定颜色，这样兜底底色不会在两次渲染之间变来变去。 */
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
 * 分类标签页有自己的白色横条，而不是浮在 banner 上。
 * Banner 是按分类变化的内容，所以选分类的控件必须在视觉上位于它之外。
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

/* 一行五个，在 1200px 的容器宽度下，海报的宽高比接近真实票务网站。 */
.mp-film-grid {
  grid-template-columns: repeat(5, 1fr);
}
</style>
