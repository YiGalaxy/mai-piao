<template>
  <div>
    <div class="page-head">
      <h2>场馆</h2>
    </div>
    <p class="hint">
      只读。场馆和场地是物理事实，不是排期的一部分 —— 改动它们等于改动已经卖出去的座位。
    </p>

    <el-card shadow="never" v-loading="loading">
      <el-collapse>
        <el-collapse-item v-for="venue in venues" :key="venue.id" :name="venue.id">
          <template #title>
            <span class="venue-name">{{ venue.name }}</span>
            <el-tag size="small" style="margin-left: 10px">{{ venue.venueType }}</el-tag>
            <span class="muted" style="margin-left: 10px">{{ venue.city }} · {{ venue.address }}</span>
          </template>

          <el-table :data="venue.places" size="small">
            <el-table-column prop="name" label="场地" min-width="140" />
            <el-table-column prop="placeType" label="类型" width="100" />
            <el-table-column prop="seatingMode" label="座位形式" width="110" />
            <el-table-column label="行 × 列" width="110">
              <template #default="{ row }">{{ row.rowCount }} × {{ row.colCount }}</template>
            </el-table-column>
            <el-table-column prop="seatCount" label="座位数" width="90" />
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { fetchVenues } from '../api/admin'

const venues = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    venues.value = (await fetchVenues()) || []
  } catch {
    venues.value = []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.venue-name {
  font-weight: 600;
}

.muted {
  font-size: 13px;
  color: var(--mp-text-muted);
}
</style>
