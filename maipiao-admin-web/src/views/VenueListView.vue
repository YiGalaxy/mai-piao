<template>
  <div>
    <div class="page-head">
      <h2>场馆</h2>
      <el-button type="primary" @click="openVenue()">新建场馆</el-button>
    </div>

    <p class="hint">
      场馆和场地是一次建完的 —— 场馆就是它的场地。没有场地的场馆排不了任何演出，
      所以建场馆时就要说清楚它里面有什么。
    </p>

    <el-card shadow="never" v-loading="loading">
      <el-collapse v-model="expanded">
        <el-collapse-item v-for="venue in venues" :key="venue.id" :name="venue.id">
          <template #title>
            <span class="venue-name">{{ venue.name }}</span>
            <el-tag size="small" style="margin-left: 10px">
              {{ venueTypeLabel(venue.venueType) }}
            </el-tag>
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

          <el-table :data="venue.places" size="small" empty-text="还没有场地，这个场馆排不了演出">
            <el-table-column prop="name" label="场地" min-width="130" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ placeTypeLabel(row.placeType) }}</template>
            </el-table-column>
            <el-table-column label="座位形式" width="100">
              <template #default="{ row }">{{ seatingLabel(row.seatingMode) }}</template>
            </el-table-column>
            <el-table-column label="排列" width="100">
              <template #default="{ row }">{{ row.rowCount }} 排 × {{ row.colCount }} 列</template>
            </el-table-column>
            <el-table-column label="座位数" width="150">
              <template #default="{ row }">
                <!--
                  两个数并排显示。声明的是个标签，算出来的才是场次真正会拿到的数。
                  两者允许不同，但改的人应该同时看到，而不是在一个场次建出来之后才发现。
                -->
                <span :class="{ mismatch: row.seatCount !== row.actualSeatCount }">
                  {{ row.actualSeatCount }} 座
                </span>
                <span class="muted" v-if="row.seatCount !== row.actualSeatCount">
                  （声明 {{ row.seatCount }}）
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

    <!-- 建场馆：场馆信息 + 场地列表，一次提交 -->
    <el-dialog v-model="venueDialog" :title="venueForm.id ? '编辑场馆' : '新建场馆'" width="820px">
      <el-form :model="venueForm" label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="venueForm.name" placeholder="例如：广州天河体育中心" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="venueForm.venueType" style="width: 200px">
            <el-option
              v-for="t in VENUE_TYPES"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="城市/区">
          <el-input v-model="venueForm.district" style="width: 200px" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="venueForm.address" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="venueForm.phone" style="width: 200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="venueForm.status" :active-value="1" :inactive-value="0" />
          <span class="muted" style="margin-left: 8px">停用的场馆不会出现在排期选择里</span>
        </el-form-item>

        <!-- 只有新建时才能一起填场地；已存在的场馆用列表里的「添加场地」 -->
        <template v-if="!venueForm.id">
          <el-divider content-position="left">场地</el-divider>
          <p class="hint" style="margin-top: 0">
            一个场馆可以有多个场地（主馆、副馆、看台区……），各自有自己的座位排列。
            座位号按「排-列」自动生成，过道列和损坏座会从座位里剔除。
          </p>

          <div v-for="(place, i) in venueForm.places" :key="i" class="place-block">
            <div class="place-block-head">
              <span class="place-index">场地 {{ i + 1 }}</span>
              <el-button link type="danger" @click="venueForm.places.splice(i, 1)">移除</el-button>
            </div>

            <el-form-item label="名称" required>
              <el-input v-model="place.name" placeholder="例如：主体育场" style="width: 240px" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="place.placeType" style="width: 160px">
                <el-option
                  v-for="t in PLACE_TYPES"
                  :key="t.value"
                  :label="t.label"
                  :value="t.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="座位形式">
              <el-radio-group v-model="place.seatingMode">
                <el-radio value="SEATED">对号入座</el-radio>
                <el-radio value="STANDING">站席</el-radio>
                <el-radio value="MIXED">混合</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="排数" required>
              <el-input-number v-model="place.rowCount" :min="1" :max="200" />
              <span class="muted" style="margin-left: 12px">每排</span>
              <el-input-number v-model="place.colCount" :min="1" :max="400" style="margin-left: 8px" />
              <span class="muted" style="margin-left: 8px">列</span>
            </el-form-item>
            <el-form-item label="过道列">
              <el-input v-model="place.aisleText" placeholder="逗号分隔，例如 17,35" style="width: 240px" />
              <span class="muted" style="margin-left: 8px">这些列不放座位</span>
            </el-form-item>
            <el-form-item label="损坏座">
              <el-input v-model="place.brokenText" placeholder="逗号分隔，例如 1-1,1-52" style="width: 240px" />
              <span class="muted" style="margin-left: 8px">「排-列」</span>
            </el-form-item>

            <el-form-item label="座位数">
              <!--
                实时算出来，因为「多少个座位」是建场馆时最该先看到的一个数 ——
                而它等于排数 × (列数 - 过道数) - 损坏座，不是填进去的。
              -->
              <span class="seat-count">{{ seatCountOf(place) }} 座</span>
              <span class="muted" style="margin-left: 8px">
                = {{ place.rowCount }} 排 × ({{ place.colCount }} 列
                <template v-if="aislesOf(place).length">
                  − {{ aislesOf(place).length }} 过道列</template>)
                <template v-if="brokenOf(place).length">
                  − {{ brokenOf(place).length }} 损坏座</template>
              </span>
            </el-form-item>
          </div>

          <el-form-item>
            <el-button link type="primary" @click="addPlaceRow">+ 添加场地</el-button>
          </el-form-item>
        </template>
      </el-form>

      <template #footer>
        <el-button @click="venueDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveVenue">
          {{ venueForm.id ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 已有场馆加/改单个场地 -->
    <el-dialog v-model="placeDialog" :title="placeForm.id ? '编辑场地' : '添加场地'" width="640px">
      <el-form :model="placeForm" label-width="100px">
        <el-form-item label="所属场馆">
          <el-input :model-value="placeForm.venueName" disabled />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="placeForm.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="placeForm.placeType" style="width: 160px">
            <el-option
              v-for="t in PLACE_TYPES"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="座位形式">
          <el-radio-group v-model="placeForm.seatingMode">
            <el-radio value="SEATED">对号入座</el-radio>
            <el-radio value="STANDING">站席</el-radio>
            <el-radio value="MIXED">混合</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排数" required>
          <el-input-number v-model="placeForm.rowCount" :min="1" :max="200" />
          <span class="muted" style="margin-left: 12px">每排</span>
          <el-input-number v-model="placeForm.colCount" :min="1" :max="400" style="margin-left: 8px" />
          <span class="muted" style="margin-left: 8px">列</span>
        </el-form-item>
        <el-form-item label="过道列">
          <el-input v-model="placeForm.aisleText" placeholder="逗号分隔，例如 17,35" />
        </el-form-item>
        <el-form-item label="损坏座">
          <el-input v-model="placeForm.brokenText" placeholder="逗号分隔，例如 1-1,1-52" />
        </el-form-item>
        <el-form-item label="座位数">
          <span class="seat-count">{{ seatCountOf(placeForm) }} 座</span>
          <span class="muted" style="margin-left: 8px">
            已排 {{ placeForm.id ? '（改动只影响之后新建的场次）' : '' }}
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
import {
  createPlace,
  createVenueWithPlaces,
  fetchVenues,
  updatePlace,
  updateVenue
} from '../api/admin'

// 值和标签分开：存的是英文枚举，给用户看的是中文。
// 直接把枚举显示出来是偷懒，用户不该知道数据库里存的是什么。
const VENUE_TYPES = [
  { value: 'STADIUM', label: '体育场' },
  { value: 'GYMNASIUM', label: '体育馆' },
  { value: 'CINEMA', label: '电影院' },
  { value: 'THEATER', label: '剧场' },
  { value: 'LIVEHOUSE', label: 'Livehouse' }
]

const PLACE_TYPES = [
  { value: 'ARENA', label: '主馆/内场' },
  { value: 'THEATER', label: '剧场厅' },
  { value: 'STUDIO', label: '小剧场' },
  { value: 'STANDING', label: '站席区' },
  { value: 'IMAX', label: 'IMAX厅' },
  { value: '3D', label: '3D厅' },
  { value: 'NORMAL', label: '普通厅' }
]

const venues = ref([])
const loading = ref(true)
const saving = ref(false)
const expanded = ref([])

const venueDialog = ref(false)
const placeDialog = ref(false)

const venueForm = reactive({
  id: null, name: '', venueType: 'STADIUM', district: '', address: '',
  phone: '', status: 1, places: []
})

const placeForm = reactive({
  id: null, venueId: '', venueName: '', name: '', placeType: 'ARENA',
  seatingMode: 'SEATED', rowCount: 20, colCount: 30, status: 1,
  aisleText: '', brokenText: ''
})

function venueTypeLabel(value) {
  return VENUE_TYPES.find((t) => t.value === value)?.label || value
}

function placeTypeLabel(value) {
  return PLACE_TYPES.find((t) => t.value === value)?.label || value
}

function seatingLabel(value) {
  return { SEATED: '对号入座', STANDING: '站席', MIXED: '混合' }[value] || value
}

// ------------------------------------------------------------
// 座位数
// ------------------------------------------------------------

function aislesOf(place) {
  return numbersOf(place.aisleText)
}

function brokenOf(place) {
  return tokensOf(place.brokenText)
}

/**
 * 场次会拿到多少个座位。
 *
 * <p>和服务端的算法一致：排数 ×（列数 − 过道列数）− 损坏座。
 * <p>在界面上先算一遍是为了让人**边填边看到结果** —— 这个数是建场馆时最该确认的，
 * 而它不该靠填一个数字进去，那只会和模板对不上。
 */
function seatCountOf(place) {
  if (place.seatingMode === 'STANDING') {
    // 站席按一列容量算：没有排的概念，一个座位就是一张票。
    return Math.max(0, Number(place.colCount) || 0)
  }
  const rows = Number(place.rowCount) || 0
  const cols = Math.max(0, (Number(place.colCount) || 0) - aislesOf(place).length)
  return Math.max(0, rows * cols - brokenOf(place).length)
}

function numbersOf(text) {
  return String(text || '')
    .split(',')
    .map((s) => parseInt(s.trim(), 10))
    .filter((n) => !Number.isNaN(n))
}

function tokensOf(text) {
  return String(text || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

// ------------------------------------------------------------

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

function blankPlace() {
  return {
    name: '', placeType: 'ARENA', seatingMode: 'SEATED',
    rowCount: 20, colCount: 30, aisleText: '', brokenText: '', status: 1
  }
}

function addPlaceRow() {
  venueForm.places.push(blankPlace())
}

function openVenue(venue) {
  if (venue) {
    Object.assign(venueForm, {
      id: venue.id, name: venue.name, venueType: venue.venueType,
      district: venue.district || '', address: venue.address || '',
      phone: venue.phone || '', status: venue.status, places: []
    })
  } else {
    Object.assign(venueForm, {
      id: null, name: '', venueType: 'STADIUM', district: '', address: '',
      phone: '', status: 1, places: [blankPlace()]
    })
  }
  venueDialog.value = true
}

async function saveVenue() {
  if (!venueForm.name.trim()) {
    ElMessage.warning('请填写场馆名称')
    return
  }
  const named = venueForm.places.filter((p) => p.name && p.name.trim())
  if (!venueForm.id && named.length === 0) {
    ElMessage.warning('至少需要一个场地，否则这个场馆排不了演出')
    return
  }

  saving.value = true
  try {
    const venue = {
      name: venueForm.name,
      venueType: venueForm.venueType,
      district: venueForm.district,
      address: venueForm.address,
      phone: venueForm.phone,
      status: venueForm.status
    }

    if (venueForm.id) {
      await updateVenue(venueForm.id, venue)
    } else {
      // 一次建完。分成两次调用会留下「场馆建好了、场地没建成」的中间状态，
      // 那种场馆排不了演出也卖不了票。
      await createVenueWithPlaces({
        venue,
        // 不带 venueId：场地属于哪个场馆，是服务端刚建出来的那个，
        // 前端无从知道。传个占位值只会掩盖一个本来就不存在的字段。
        places: named.map((p) => ({
          name: p.name,
          placeType: p.placeType,
          seatingMode: p.seatingMode,
          rowCount: p.rowCount,
          colCount: p.colCount,
          seatCount: seatCountOf(p),
          status: p.status,
          seatTemplate: JSON.stringify({
            rows: p.rowCount,
            cols: p.colCount,
            aisleCols: aislesOf(p),
            brokenSeats: brokenOf(p),
            coupleSeats: []
          })
        }))
      })
    }

    ElMessage.success('已保存')
    venueDialog.value = false
    await load()
  } catch {
    // request.js 已经提示过原因
  } finally {
    saving.value = false
  }
}

function openPlace(venue, place) {
  const template = parseTemplate(place?.seatTemplate)
  Object.assign(placeForm, place
    ? {
        id: place.id, venueId: venue.id, venueName: venue.name, name: place.name,
        placeType: place.placeType, seatingMode: place.seatingMode,
        rowCount: place.rowCount, colCount: place.colCount, status: place.status,
        aisleText: (template.aisleCols || []).join(','),
        brokenText: (template.brokenSeats || []).join(',')
      }
    : {
        id: null, venueId: venue.id, venueName: venue.name, name: '',
        placeType: 'ARENA', seatingMode: 'SEATED', rowCount: 20, colCount: 30,
        status: 1, aisleText: '', brokenText: ''
      })
  placeDialog.value = true
}

async function savePlace() {
  if (!placeForm.name.trim()) {
    ElMessage.warning('请填写场地名称')
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
      seatCount: seatCountOf(placeForm),
      status: placeForm.status,
      seatTemplate: JSON.stringify({
        rows: placeForm.rowCount,
        cols: placeForm.colCount,
        aisleCols: aislesOf(placeForm),
        brokenSeats: brokenOf(placeForm),
        coupleSeats: []
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
    // request.js 已经提示过原因
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

/* 只在两个数对不上时着色 —— 那才是值得看一眼的情况 */
.mismatch {
  color: #e6a23c;
  font-weight: 600;
}

.place-block {
  border: 1px solid var(--mp-border);
  border-radius: 8px;
  padding: 14px 16px 4px;
  margin-bottom: 12px;
  background: #fafbfc;
}

.place-block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.place-index {
  font-weight: 600;
  font-size: 14px;
}

.seat-count {
  font-size: 16px;
  font-weight: 700;
  color: #ff6700;
}
</style>
