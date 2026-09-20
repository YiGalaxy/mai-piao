<template>
  <div>
    <div class="page-head">
      <h2>场馆</h2>
      <el-button type="primary" @click="openVenue()">新建场馆</el-button>
    </div>

    <p class="hint">
      场馆和场地是演出得以存在的前提。座位模板决定每个场地有哪些座位 ——
      过道、损坏座、情侣座都在里面，新建场次时按它生成座位行。
      改动模板只影响**之后**新建的场次，已排期的场次保留它们自己的座位。
    </p>

    <el-card shadow="never" v-loading="loading">
      <el-collapse v-model="expanded">
        <el-collapse-item v-for="venue in venues" :key="venue.id" :name="venue.id">
          <template #title>
            <span class="venue-name">{{ venue.name }}</span>
            <el-tag size="small" style="margin-left: 10px">{{ venue.venueType }}</el-tag>
            <el-tag v-if="venue.status !== 1" size="small" type="info" style="margin-left: 6px">
              已停用
            </el-tag>
            <span class="muted" style="margin-left: 10px">
              {{ venue.district }} · {{ venue.phone }}
            </span>
            <span class="spacer" />
            <el-button link type="primary" @click.stop="openVenue(venue)">编辑</el-button>
          </template>

          <div class="place-head">
            <span class="muted">{{ venue.address }}</span>
            <el-button link type="primary" @click="openPlace(venue)">+ 添加场地</el-button>
          </div>

          <el-table :data="venue.places" size="small" empty-text="还没有场地">
            <el-table-column prop="name" label="场地" min-width="130" />
            <el-table-column prop="placeType" label="类型" width="90" />
            <el-table-column prop="seatingMode" label="座位形式" width="100" />
            <el-table-column label="行 × 列" width="100">
              <template #default="{ row }">{{ row.rowCount }} × {{ row.colCount }}</template>
            </el-table-column>
            <el-table-column label="座位数" width="140">
              <template #default="{ row }">
                <!--
                  Two numbers on purpose. The declared one is a label; the
                  computed one is what a session will actually get. They are
                  allowed to differ, and a person editing should see both
                  rather than discover the gap in the size of a session.
                -->
                <span :class="{ mismatch: row.seatCount !== row.actualSeatCount }">
                  实际 {{ row.actualSeatCount }}
                </span>
                <span class="muted" v-if="row.seatCount !== row.actualSeatCount">
                  / 声明 {{ row.seatCount }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="已排场次" width="100">
              <template #default="{ row }">{{ row.sessionCount }} 场</template>
            </el-table-column>
            <el-table-column label="" width="80">
              <template #default="{ row }">
                <el-button link type="primary" @click="openPlace(venue, row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- Venue form -->
    <el-dialog v-model="venueDialog" :title="venueForm.id ? '编辑场馆' : '新建场馆'" width="560px">
      <el-form :model="venueForm" label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="venueForm.name" placeholder="例如：广州天河体育中心" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="venueForm.venueType">
            <el-option v-for="t in VENUE_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="城市/区">
          <el-input v-model="venueForm.district" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="venueForm.address" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="venueForm.phone" />
        </el-form-item>
        <el-form-item label="经纬度">
          <el-input v-model="venueForm.longitude" placeholder="经度" style="width: 130px" />
          <el-input v-model="venueForm.latitude" placeholder="纬度" style="width: 130px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="venueForm.status" :active-value="1" :inactive-value="0" />
          <span class="muted" style="margin-left: 8px">停用的场馆不会出现在排期选择里</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="venueDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveVenue">保存</el-button>
      </template>
    </el-dialog>

    <!-- Place form -->
    <el-dialog v-model="placeDialog" :title="placeForm.id ? '编辑场地' : '添加场地'" width="640px">
      <el-form :model="placeForm" label-width="100px">
        <el-form-item label="所属场馆">
          <el-input :model-value="placeForm.venueName" disabled />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="placeForm.name" placeholder="例如：主体育场" />
        </el-form-item>
        <el-form-item label="场地类型" required>
          <el-select v-model="placeForm.placeType">
            <el-option v-for="t in PLACE_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="座位形式" required>
          <el-radio-group v-model="placeForm.seatingMode">
            <el-radio value="SEATED">对号入座</el-radio>
            <el-radio value="STANDING">站席</el-radio>
            <el-radio value="MIXED">混合</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="行数" required>
          <el-input-number v-model="placeForm.rowCount" :min="1" :max="200" />
        </el-form-item>
        <el-form-item label="列数" required>
          <el-input-number v-model="placeForm.colCount" :min="1" :max="400" />
        </el-form-item>

        <el-divider content-position="left">座位模板</el-divider>
        <p class="hint" style="margin-top: 0">
          过道和损坏座会从座位里剔除，剔除后剩余的座位数就是场次的座位数。
          站席填 1 行、列数等于容量即可。
        </p>

        <el-form-item label="过道列">
          <el-input v-model="aisleText" placeholder="逗号分隔，例如 17,35" />
        </el-form-item>
        <el-form-item label="损坏座">
          <el-input v-model="brokenText" placeholder="逗号分隔，例如 1-1,1-52" />
        </el-form-item>
        <el-form-item label="情侣座">
          <el-input v-model="coupleText" placeholder="逗号分隔，例如 7-8,7-9" />
        </el-form-item>

        <el-form-item label="声明座位数">
          <el-input-number v-model="placeForm.seatCount" :min="0" />
          <span class="muted" style="margin-left: 8px">
            仅作标签；场次按模板算出的座位数为准
          </span>
        </el-form-item>

        <el-form-item label="状态">
          <el-switch v-model="placeForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="placeDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePlace">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createPlace, createVenue, fetchVenues, updatePlace, updateVenue } from '../api/admin'

const VENUE_TYPES = ['CINEMA', 'GYMNASIUM', 'LIVEHOUSE', 'STADIUM', 'THEATER']
const PLACE_TYPES = ['IMAX', '3D', 'NORMAL', 'ARENA', 'THEATER', 'STUDIO', 'STANDING']

const venues = ref([])
const loading = ref(true)
const saving = ref(false)
const expanded = ref([])

const venueDialog = ref(false)
const placeDialog = ref(false)

const venueForm = reactive({
  id: null, name: '', venueType: 'STADIUM', district: '', address: '',
  phone: '', longitude: '', latitude: '', status: 1
})

const placeForm = reactive({
  id: null, venueId: '', venueName: '', name: '', placeType: 'ARENA',
  seatingMode: 'SEATED', rowCount: 20, colCount: 30, seatCount: 0, status: 1
})

// The template is stored as JSON but edited as three comma lists. Building the
// JSON in the view and never showing it is the difference between a form
// somebody can fill in and one they have to look up the schema for.
const aisleText = ref('')
const brokenText = ref('')
const coupleText = ref('')

onMounted(load)

async function load() {
  loading.value = true
  try {
    venues.value = (await fetchVenues()) || []
  } catch {
    venues.value = []
  } finally {
    loading.value = false
  }
}

function openVenue(venue) {
  Object.assign(venueForm, venue
    ? {
        id: venue.id, name: venue.name, venueType: venue.venueType,
        district: venue.district || '', address: venue.address || '',
        phone: venue.phone || '', longitude: venue.longitude ?? '',
        latitude: venue.latitude ?? '', status: venue.status
      }
    : {
        id: null, name: '', venueType: 'STADIUM', district: '', address: '',
        phone: '', longitude: '', latitude: '', status: 1
      })
  venueDialog.value = true
}

async function saveVenue() {
  if (!venueForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: venueForm.name,
      venueType: venueForm.venueType,
      district: venueForm.district,
      address: venueForm.address,
      phone: venueForm.phone,
      longitude: venueForm.longitude === '' ? null : venueForm.longitude,
      latitude: venueForm.latitude === '' ? null : venueForm.latitude,
      status: venueForm.status
    }
    if (venueForm.id) {
      await updateVenue(venueForm.id, payload)
    } else {
      await createVenue(payload)
    }
    ElMessage.success('已保存')
    venueDialog.value = false
    await load()
  } catch {
    // surfaced
  } finally {
    saving.value = false
  }
}

function openPlace(venue, place) {
  if (place) {
    const template = parseTemplate(place.seatTemplate)
    aisleText.value = (template.aisleCols || []).join(',')
    brokenText.value = (template.brokenSeats || []).join(',')
    coupleText.value = (template.coupleSeats || []).flat().join(',')
  } else {
    aisleText.value = ''
    brokenText.value = ''
    coupleText.value = ''
  }

  Object.assign(placeForm, place
    ? {
        id: place.id, venueId: venue.id, venueName: venue.name, name: place.name,
        placeType: place.placeType, seatingMode: place.seatingMode,
        rowCount: place.rowCount, colCount: place.colCount,
        seatCount: place.seatCount, status: place.status
      }
    : {
        id: null, venueId: venue.id, venueName: venue.name, name: '',
        placeType: 'ARENA', seatingMode: 'SEATED', rowCount: 20, colCount: 30,
        seatCount: 0, status: 1
      })
  placeDialog.value = true
}

async function savePlace() {
  if (!placeForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      venueId: placeForm.venueId,
      name: placeForm.name,
      placeType: placeForm.placeType,
      seatingMode: placeForm.seatingMode,
      rowCount: placeForm.rowCount,
      colCount: placeForm.colCount,
      seatCount: placeForm.seatCount,
      status: placeForm.status,
      seatTemplate: JSON.stringify({
        rows: placeForm.rowCount,
        cols: placeForm.colCount,
        aisleCols: numbersOf(aisleText.value),
        brokenSeats: tokensOf(brokenText.value),
        coupleSeats: pairsOf(coupleText.value)
      })
    }
    if (placeForm.id) {
      await updatePlace(placeForm.id, payload)
    } else {
      await createPlace(payload)
    }
    ElMessage.success('已保存')
    placeDialog.value = false
    await load()
  } catch {
    // surfaced
  } finally {
    saving.value = false
  }
}

function parseTemplate(json) {
  if (!json) return {}
  try {
    return JSON.parse(json)
  } catch {
    return {}
  }
}

function numbersOf(text) {
  return text.split(',').map((s) => parseInt(s.trim(), 10)).filter((n) => !Number.isNaN(n))
}

function tokensOf(text) {
  return text.split(',').map((s) => s.trim()).filter(Boolean)
}

/** Couple seats come in adjacent pairs; a flat list is paired up in order. */
function pairsOf(text) {
  const seats = tokensOf(text)
  const pairs = []
  for (let i = 0; i + 1 < seats.length; i += 2) {
    pairs.push([seats[i], seats[i + 1]])
  }
  return pairs
}
</script>

<style scoped>
.venue-name {
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.place-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.muted {
  font-size: 13px;
  color: var(--mp-text-muted);
}

/* Only coloured when the two numbers disagree, which is the case worth seeing. */
.mismatch {
  color: #e6a23c;
  font-weight: 600;
}
</style>
