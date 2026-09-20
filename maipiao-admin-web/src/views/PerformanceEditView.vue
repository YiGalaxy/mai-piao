<template>
  <div>
    <div class="page-head">
      <h2>新建演出</h2>
      <el-button @click="router.back()">返回</el-button>
    </div>

    <p class="hint">
      这里只创建「演出」本身。日期、场馆和票档是下一步的事 ——
      一场演出可以在不同城市不同场馆各演一晚，每一晚都是独立在售的。
    </p>

    <el-card shadow="never">
      <el-form :model="form" label-width="100px" style="max-width: 720px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.category">
            <el-radio-button
              v-for="c in CATEGORIES"
              :key="c.value"
              :value="c.value"
            >
              {{ c.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="名称" required>
          <el-input v-model="form.title" placeholder="例如：陈奕迅 FEAR AND DREAMS 世界巡回演唱会·深圳站" />
        </el-form-item>

        <el-form-item label="英文名">
          <el-input v-model="form.enTitle" />
        </el-form-item>

        <template v-if="isFilm">
          <el-form-item label="导演">
            <el-input v-model="form.director" />
          </el-form-item>
          <el-form-item label="主演">
            <el-input v-model="form.actors" />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="艺人">
            <el-input v-model="form.artist" placeholder="例如：陈奕迅" />
          </el-form-item>
          <el-form-item label="主办方">
            <el-input v-model="form.organizer" />
          </el-form-item>
        </template>

        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="逗号分隔，例如：流行,粤语,巡演" />
        </el-form-item>

        <el-form-item label="时长(分钟)">
          <el-input-number v-model="form.duration" :min="1" :max="600" />
        </el-form-item>

        <el-form-item label="首演日期">
          <el-date-picker
            v-model="form.showDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="可以晚于今天很久"
          />
        </el-form-item>

        <el-form-item label="简介">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="onSubmit">
            创建并去排期
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createProject } from '../api/admin'

const router = useRouter()

const CATEGORIES = [
  { value: 'CONCERT', label: '演唱会' },
  { value: 'TALK_SHOW', label: '脱口秀' },
  { value: 'THEATER', label: '话剧' },
  { value: 'MUSICAL', label: '音乐剧' },
  { value: 'MOVIE', label: '电影' }
]

const form = reactive({
  title: '',
  enTitle: '',
  category: 'CONCERT',
  artist: '',
  organizer: '',
  director: '',
  actors: '',
  tags: '',
  duration: 120,
  showDate: '',
  description: ''
})

const saving = ref(false)

/** A film credits a director; everything else credits an artist. */
const isFilm = computed(() => form.category === 'MOVIE')

async function onSubmit() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const projectId = await createProject({ ...form, showDate: form.showDate || null })
    ElMessage.success('已创建，接下来排期')
    // Straight to the dates: a project with no sessions sells nothing, so it
    // is not a finished piece of work.
    router.replace(`/performances/${projectId}`)
  } catch {
    // surfaced
  } finally {
    saving.value = false
  }
}
</script>
