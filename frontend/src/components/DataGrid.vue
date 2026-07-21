<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { TableInfo, TableMetadata } from '../types'
import MetadataPanel from './MetadataPanel.vue'
import RowEditor from './RowEditor.vue'

const props = defineProps<{ table: TableInfo }>()
const emit = defineEmits<{ error: [message: string] }>()

const metadata = ref<TableMetadata | null>(null)
const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const page = ref(0)
const size = ref(50)
const total = ref(0)
const query = ref('')
const sort = ref('')
const direction = ref<'asc' | 'desc'>('asc')
const editorMode = ref<'create' | 'edit' | null>(null)
const editingRow = ref<Record<string, unknown> | null>(null)
const showMetadata = ref(false)
let searchTimer: number | undefined

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const rangeStart = computed(() => total.value === 0 ? 0 : page.value * size.value + 1)
const rangeEnd = computed(() => Math.min(total.value, (page.value + 1) * size.value))

watch(
  () => [props.table.schema, props.table.name],
  async () => {
    page.value = 0
    query.value = ''
    sort.value = ''
    await loadMetadataAndRows()
  },
)

watch(size, () => {
  page.value = 0
  loadRows()
})

onMounted(loadMetadataAndRows)

async function loadMetadataAndRows() {
  loading.value = true
  try {
    metadata.value = await api.metadata(props.table.schema, props.table.name)
    sort.value = metadata.value.primaryKeys[0] || metadata.value.columns[0]?.name || ''
    await loadRows()
  } catch (cause) {
    emitError(cause)
  } finally {
    loading.value = false
  }
}

async function loadRows() {
  if (!metadata.value) return
  loading.value = true
  try {
    const response = await api.rows(props.table.schema, props.table.name, {
      page: page.value,
      size: size.value,
      q: query.value,
      sort: sort.value,
      direction: direction.value,
    })
    rows.value = response.rows
    total.value = response.total
  } catch (cause) {
    emitError(cause)
  } finally {
    loading.value = false
  }
}

function scheduleSearch() {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 0
    loadRows()
  }, 350)
}

function changeSort(column: string) {
  if (sort.value === column) {
    direction.value = direction.value === 'asc' ? 'desc' : 'asc'
  } else {
    sort.value = column
    direction.value = 'asc'
  }
  loadRows()
}

function openCreate() {
  editingRow.value = null
  editorMode.value = 'create'
}

function openEdit(row: Record<string, unknown>) {
  editingRow.value = row
  editorMode.value = 'edit'
}

function rowIdentity(row: Record<string, unknown>) {
  if (!metadata.value) return { keys: {}, rowId: undefined }
  const keys = Object.fromEntries(metadata.value.primaryKeys.map((key) => [key, row[key]]))
  return { keys, rowId: String(row.__rowId ?? '') }
}

async function submitRow(values: Record<string, unknown>) {
  if (!metadata.value || !editorMode.value) return
  try {
    if (editorMode.value === 'create') {
      await api.create(props.table.schema, props.table.name, { values })
    } else if (editingRow.value) {
      await api.update(props.table.schema, props.table.name, {
        values,
        ...rowIdentity(editingRow.value),
      })
    }
    editorMode.value = null
    editingRow.value = null
    await loadRows()
  } catch (cause) {
    emitError(cause)
  }
}

async function removeRow(row: Record<string, unknown>) {
  if (!metadata.value) return
  const label = metadata.value.primaryKeys.length
    ? metadata.value.primaryKeys.map((key) => `${key}=${String(row[key])}`).join(', ')
    : `ctid=${String(row.__rowId)}`
  if (!window.confirm(`确认删除记录 ${label}？此操作不可撤销。`)) return

  try {
    await api.remove(props.table.schema, props.table.name, rowIdentity(row))
    if (rows.value.length === 1 && page.value > 0) page.value -= 1
    await loadRows()
  } catch (cause) {
    emitError(cause)
  }
}

function displayValue(value: unknown): string {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return String(value)
}

function emitError(cause: unknown) {
  emit('error', cause instanceof Error ? cause.message : '数据库操作失败')
}
</script>

<template>
  <section class="data-view">
    <header class="data-header">
      <div>
        <p class="eyebrow">{{ table.schema }}</p>
        <h1>{{ table.name }}</h1>
      </div>
      <div class="header-actions">
        <button class="secondary" @click="showMetadata = true">字段结构</button>
        <button class="secondary" @click="loadRows">刷新</button>
        <button class="primary" :disabled="!metadata?.canInsert" @click="openCreate">新增记录</button>
      </div>
    </header>

    <div v-if="metadata && metadata.primaryKeys.length === 0" class="warning-banner">
      该表没有主键，更新和删除会使用当前行的 ctid。并发修改或 VACUUM FULL 后，旧 ctid 可能失效。
    </div>

    <div class="toolbar">
      <div class="search-box">
        <span>⌕</span>
        <input v-model="query" placeholder="搜索所有字段…" @input="scheduleSearch" />
      </div>
      <label>
        每页
        <select v-model.number="size">
          <option :value="25">25</option>
          <option :value="50">50</option>
          <option :value="100">100</option>
          <option :value="200">200</option>
        </select>
      </label>
    </div>

    <div class="grid-shell" :class="{ loading }">
      <table v-if="metadata">
        <thead>
          <tr>
            <th class="action-column">操作</th>
            <th
              v-for="column in metadata.columns"
              :key="column.name"
              class="sortable"
              @click="changeSort(column.name)"
            >
              <span>{{ column.name }}</span>
              <small>{{ column.formattedType }}</small>
              <b v-if="sort === column.name">{{ direction === 'asc' ? '↑' : '↓' }}</b>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="String(row.__rowId)">
            <td class="row-actions">
              <button :disabled="!metadata.canUpdate" @click="openEdit(row)">编辑</button>
              <button class="danger-link" :disabled="!metadata.canDelete" @click="removeRow(row)">删除</button>
            </td>
            <td
              v-for="column in metadata.columns"
              :key="column.name"
              :class="{ null: row[column.name] === null || row[column.name] === undefined }"
              :title="displayValue(row[column.name])"
            >
              {{ displayValue(row[column.name]) }}
            </td>
          </tr>
          <tr v-if="!loading && rows.length === 0">
            <td class="empty-cell" :colspan="metadata.columns.length + 1">没有匹配的数据</td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="loading-overlay">正在读取 PostgreSQL…</div>
    </div>

    <footer class="pagination">
      <span>显示 {{ rangeStart }}–{{ rangeEnd }}，共 {{ total }} 条</span>
      <div>
        <button class="secondary compact" :disabled="page === 0" @click="page--; loadRows()">上一页</button>
        <span>第 {{ page + 1 }} / {{ pageCount }} 页</span>
        <button class="secondary compact" :disabled="page + 1 >= pageCount" @click="page++; loadRows()">下一页</button>
      </div>
    </footer>

    <RowEditor
      v-if="metadata && editorMode"
      :mode="editorMode"
      :metadata="metadata"
      :row="editingRow"
      @close="editorMode = null"
      @submit="submitRow"
    />
    <MetadataPanel v-if="metadata && showMetadata" :metadata="metadata" @close="showMetadata = false" />
  </section>
</template>
