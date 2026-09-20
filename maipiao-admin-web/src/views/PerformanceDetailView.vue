<template>
  <div>
    <div class="page-head">
      <h2>排期</h2>
      <el-button @click="router.push('/performances')">返回列表</el-button>
    </div>

    <p class="hint">
      每一行是一晚，各有各的座位和库存。巡演三晚就加三次 ——
      没有「每天循环」这种东西，那是电影院的排片方式。
    </p>

    <!-- Dates already on sale -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>已排场次</template>
      <el-table :data="sessions" v-loading="loading" empty-text="还没有排期，演出不会出现在前台">
        <el-table-column label="日期" width="130">
          <template #default="{ row }">{{ dateOf(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="开演" width="90">
          <template #default="{ row }">{{ timeOf(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="座位数" width="90" prop="totalSeat" />
        <el-table-column label="已售" width="80" prop="soldSeat" />
        <el-table-column label="售法" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.seatMode === 1 ? 'warning' : 'info'">
              {{ row.seatMode === 1 ? '系统分配' : '自选座位' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="抢购" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.rushMode === 1" size="small" type="danger">抢购</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="" width="90">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              :disabled="row.soldSeat > 0"
              @click="onDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Add one -->
    <el-card shadow="never">
      <template #header>新增一场</template>

      <el-form :model="form" label-width="110px" style="max-width: 820px">
        <el-form-item label="场馆" required>
          <el-select v-model="form.venueId" placeholder="选择场馆" @change="onVenueChange">
            <el-option
              v-for="venue in venues"
              :key="venue.id"
              :label="venueLabel(venue)"
              :value="venue.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="场地" required>
          <el-select v-model="form.placeId" placeholder="选择场地" :disabled="!form.venueId">
            <el-option
              v-for="place in placesOfVenue"
              :key="place.id"
              :label="placeLabel(place)"
              :value="place.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="日期" required>
          <el-date-picker v-model="form.showDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>

        <el-form-item label="开演时间" required>
          <el-time-select
            v-model="form.startTime"
            start="10:00"
            step="00:30"
            end="23:00"
            placeholder="选择时间"
          />
        </el-form-item>

        <el-form-item label="售票方式">
          <el-radio-group v-model="form.seatMode">
            <el-radio :value="0">观众自选座位</el-radio>
            <el-radio :value="1">系统分配连座</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="">
          <span class="muted">
            电影院、小剧场用自选；体育馆演唱会座位上千，观众挑不过来，用分配。
          </span>
        </el-form-item>

        <el-form-item label="每单限购">
          <el-input-number v-model="form.purchaseLimit" :min="0" :max="10" />
          <span class="muted" style="margin-left: 8px">0 表示不限</span>
        </el-form-item>

        <el-form-item label="实名观演">
          <el-switch v-model="form.requireRealName" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <el-form-item label="抢购模式">
          <el-switch v-model="form.rushMode" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <el-form-item v-if="form.rushMode === 1" label="开抢时间" required>
          <el-date-picker
            v-model="form.rushStartTime"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="到点才开放排队"
          />
        </el-form-item>

        <!-- Price bands -->
        <el-divider content-position="left">票档</el-divider>
        <p class="hint" style="margin-top: 0">
          按排划分，这是场馆实际卖票的方式：「1 到 8 排是 VIP」。
          结束排填 0 表示直到最后一行。
        </p>

        <div v-for="(tier, i) in form.tierSpecs" :key="i" class="tier-row">
          <el-input v-model="tier.name" placeholder="票档名" style="width: 130px" />
          <el-input-number v-model="tier.price" :min="1" :precision="2" />
          <span class="muted">第</span>
          <el-input-number v-model="tier.rowStart" :min="1" :controls="false" style="width: 68px" />
          <span class="muted">到</span>
          <el-input-number v-model="tier.rowEnd" :min="0" :controls="false" style="width: 68px" />
          <span class="muted">排</span>
          <el-color-picker v-model="tier.color" />
          <el-button link type="danger" @click="form.tierSpecs.splice(i, 1)">删除</el-button>
        </div>

        <el-form-item>
          <el-button link type="primary" @click="addTier">+ 添加票档</el-button>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="onSubmit">
            排期并生成座位
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createSession,
  deleteSession,
  fetchProjectSessions,
  fetchVenues
} from '../api/admin'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id

const venues = ref([])
const sessions = ref([])
const loading = ref(true)
const saving = ref(false)

const form = reactive({
  venueId: '',
  placeId: '',
  showDate: '',
  startTime: '19:30',
  seatMode: 1,
  purchaseLimit: 4,
  requireRealName: 1,
  rushMode: 0,
  rushStartTime: '',
  tierSpecs: [
    { name: '内场VIP', price: 1680, rowStart: 1, rowEnd: 6, color: '#e91e63' },
    { name: '内场', price: 1080, rowStart: 7, rowEnd: 14, color: '#ff6700' },
    { name: '看台', price: 680, rowStart: 15, rowEnd: 0, color: '#2196f3' }
  ]
})

const placesOfVenue = computed(
  () => venues.value.find((v) => v.id === form.venueId)?.places || []
)

onMounted(async () => {
  try {
    venues.value = (await fetchVenues()) || []
  } catch {
    venues.value = []
  }
  await loadSessions()
})

function onVenueChange() {
  form.placeId = ''
}

function venueLabel(venue) {
  return venue.city ? `${venue.name}（${venue.city}）` : venue.name
}

function placeLabel(place) {
  return `${place.name} · ${place.placeType} · ${place.seatCount} 座`
}

function dateOf(value) {
  return String(value).slice(0, 10)
}

function timeOf(value) {
  return String(value).slice(11, 16)
}

function addTier() {
  const last = form.tierSpecs[form.tierSpecs.length - 1]
  form.tierSpecs.push({
    name: '',
    price: 380,
    // Continues from the previous band rather than starting over, because a
    // gap between bands is seats no band covers - and those price at nothing.
    rowStart: last ? (last.rowEnd === 0 ? last.rowStart + 10 : last.rowEnd + 1) : 1,
    rowEnd: 0,
    color: '#909399'
  })
}

async function loadSessions() {
  loading.value = true
  try {
    sessions.value = (await fetchProjectSessions(projectId)) || []
  } catch {
    sessions.value = []
  } finally {
    loading.value = false
  }
}

async function onSubmit() {
  if (!form.placeId || !form.showDate || !form.startTime) {
    ElMessage.warning('场馆、日期和开演时间都要填')
    return
  }
  if (form.rushMode === 1 && !form.rushStartTime) {
    ElMessage.warning('抢购场次必须指定开抢时间')
    return
  }
  if (form.tierSpecs.length === 0) {
    ElMessage.warning('至少需要一个票档')
    return
  }

  saving.value = true
  try {
    const created = await createSession({
      projectId,
      placeId: form.placeId,
      showDate: form.showDate,
      startTime: form.startTime.length === 5 ? `${form.startTime}:00` : form.startTime,
      seatMode: form.seatMode,
      purchaseLimit: form.purchaseLimit,
      requireRealName: form.requireRealName,
      rushMode: form.rushMode,
      rushStartTime: form.rushMode === 1 ? form.rushStartTime : null,
      tierSpecs: form.tierSpecs
    })
    ElMessage.success(`已排期，生成 ${created.totalSeat} 个座位`)
    await loadSessions()
  } catch {
    // request.js surfaced the reason
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除 ${dateOf(row.startTime)} ${timeOf(row.startTime)} 这场？座位和票档会一并删除。`,
      '删除场次',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteSession(row.id)
    ElMessage.success('已删除')
    await loadSessions()
  } catch {
    // surfaced - the server refuses once a seat has been sold
  }
}
</script>

<style scoped>
.tier-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.muted {
  font-size: 13px;
  color: var(--mp-text-muted);
}
</style>
