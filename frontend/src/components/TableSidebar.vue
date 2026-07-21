<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import type { SchemaInfo, TableInfo } from '../types'

const props = defineProps<{
  schemas: SchemaInfo[]
  selected?: TableInfo | null
}>()

const emit = defineEmits<{
  select: [table: TableInfo]
  error: [message: string]
  logout: []
}>()

const activeSchema = ref(props.schemas[0]?.name ?? '')
const tables = ref<TableInfo[]>([])
const loading = ref(false)
const filter = ref('')

const visibleTables = computed(() => {
  const query = filter.value.trim().toLowerCase()
  if (!query) return tables.value
  return tables.value.filter((table) => table.name.toLowerCase().includes(query))
})

watch(
  () => props.schemas,
  (schemas) => {
    if (!activeSchema.value && schemas.length) activeSchema.value = schemas[0].name
  },
  { immediate: true },
)

watch(activeSchema, loadTables, { immediate: true })

async function loadTables() {
  if (!activeSchema.value) return
  loading.value = true
  try {
    tables.value = await api.tables(activeSchema.value)
    const currentStillExists = tables.value.some(
      (table) => table.schema === props.selected?.schema && table.name === props.selected?.name,
    )
    if (!currentStillExists && tables.value[0]) emit('select', tables.value[0])
  } catch (cause) {
    emit('error', cause instanceof Error ? cause.message : '读取数据表失败')
  } finally {
    loading.value = false
  }
}

function formatRows(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}m`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`
  return String(value)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-brand">
      <div class="sidebar-logo">PG</div>
      <div>
        <strong>CRUD Console</strong>
        <small>Database workspace</small>
      </div>
      <span class="brand-chevron">⌃</span>
    </div>

    <label class="sidebar-global-search">
      <span class="search-symbol">⌕</span>
      <input v-model="filter" aria-label="搜索数据表" placeholder="搜索数据表" />
      <kbd>/</kbd>
    </label>

    <nav class="primary-nav" aria-label="主要导航">
      <button type="button" class="nav-item">
        <span class="nav-icon">⌂</span>
        <span>概览</span>
      </button>
      <button type="button" class="nav-item active">
        <span class="nav-icon">▣</span>
        <span>数据目录</span>
        <span class="nav-count">{{ tables.length }}</span>
      </button>
    </nav>

    <div class="sidebar-divider" />

    <div class="sidebar-section-heading">
      <span>数据库对象</span>
      <button class="icon-button compact-icon" title="刷新表列表" @click="loadTables">↻</button>
    </div>

    <label class="select-label">
      <span>Schema</span>
      <select v-model="activeSchema">
        <option v-for="schema in schemas" :key="schema.name" :value="schema.name">
          {{ schema.name }}
        </option>
      </select>
    </label>

    <div class="table-list" :class="{ loading }">
      <button
        v-for="table in visibleTables"
        :key="`${table.schema}.${table.name}`"
        class="table-item"
        :class="{ active: selected?.schema === table.schema && selected?.name === table.name }"
        @click="emit('select', table)"
      >
        <span class="table-icon">▦</span>
        <span class="table-name">{{ table.name }}</span>
        <span v-if="!table.hasPrimaryKey" class="warning-dot" title="无主键，将使用 ctid 定位行">!</span>
        <span class="row-count">{{ formatRows(table.estimatedRows) }}</span>
      </button>
      <p v-if="!loading && visibleTables.length === 0" class="empty-note">没有匹配的数据表</p>
    </div>

    <footer class="sidebar-footer">
      <div class="sidebar-connection">
        <span class="status-dot" />
        <div>
          <strong>连接正常</strong>
          <small>{{ activeSchema || 'PostgreSQL' }}</small>
        </div>
      </div>
      <button class="logout-button" type="button" @click="emit('logout')">退出</button>
    </footer>
  </aside>
</template>
