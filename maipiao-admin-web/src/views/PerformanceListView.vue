<template>
  <div>
    <div class="page-head">
      <h2>演出管理</h2>
      <el-button type="primary" @click="router.push('/performances/new')">
        新建演出
      </el-button>
    </div>

    <el-card shadow="never">
      <el-radio-group v-model="category" @change="load" style="margin-bottom: 14px">
        <el-radio-button :value="''">全部</el-radio-button>
        <el-radio-button v-for="c in CATEGORIES" :key="c.value" :value="c.value">
          {{ c.label }}
        </el-radio-button>
      </el-radio-group>

      <el-table :data="projects" v-loading="loading" empty-text="还没有演出">
        <el-table-column prop="title" label="名称" min-width="260">
          <template #default="{ row }">
            <el-link type="primary" @click="router.push(`/performances/${row.id}`)">
              {{ row.title }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column prop="artist" label="艺人" width="140" />
        <el-table-column prop="showDate" label="首演" width="120" />
        <el-table-column label="已排场次" width="110">
          <template #default="{ row }">
            <el-tag :type="row.sessionCount > 0 ? 'success' : 'info'" size="small">
              {{ row.sessionCount }} 场
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchProjects } from '../api/admin'

const router = useRouter()
const projects = ref([])
const loading = ref(true)
const category = ref('')

const CATEGORIES = [
  { value: 'CONCERT', label: '演唱会' },
  { value: 'TALK_SHOW', label: '脱口秀' },
  { value: 'THEATER', label: '话剧' },
  { value: 'MUSICAL', label: '音乐剧' },
  { value: 'MOVIE', label: '电影' }
]

function categoryLabel(value) {
  return CATEGORIES.find((c) => c.value === value)?.label || value
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    projects.value = (await fetchProjects(category.value || undefined)) || []
  } catch {
    projects.value = []
  } finally {
    loading.value = false
  }
}
</script>
