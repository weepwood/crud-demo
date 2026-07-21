<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, clearCredentials, hasCredentials } from './api'
import DataGrid from './components/DataGrid.vue'
import LoginPanel from './components/LoginPanel.vue'
import TableSidebar from './components/TableSidebar.vue'
import type { SchemaInfo, TableInfo } from './types'

const authenticated = ref(hasCredentials())
const schemas = ref<SchemaInfo[]>([])
const selectedTable = ref<TableInfo | null>(null)
const loading = ref(false)
const error = ref('')
let errorTimer: number | undefined

onMounted(() => {
  window.addEventListener('crud-demo:unauthorized', logout)
  if (authenticated.value) initialize()
})

onBeforeUnmount(() => {
  window.removeEventListener('crud-demo:unauthorized', logout)
})

async function initialize() {
  authenticated.value = true
  loading.value = true
  try {
    schemas.value = await api.schemas()
  } catch (cause) {
    showError(cause instanceof Error ? cause.message : '无法连接后端')
    if (!hasCredentials()) authenticated.value = false
  } finally {
    loading.value = false
  }
}

function logout() {
  clearCredentials()
  authenticated.value = false
  schemas.value = []
  selectedTable.value = null
}

function showError(message: string) {
  error.value = message
  window.clearTimeout(errorTimer)
  errorTimer = window.setTimeout(() => { error.value = '' }, 6000)
}
</script>

<template>
  <LoginPanel v-if="!authenticated" @authenticated="initialize" />

  <div v-else class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="mini-brand">PG</span>
        <div>
          <strong>CRUD Console</strong>
          <small>Metadata-driven database manager</small>
        </div>
      </div>
      <div class="connection-state">
        <span class="status-dot" /> PostgreSQL connected
        <button class="secondary compact" @click="logout">退出</button>
      </div>
    </header>

    <div class="workspace">
      <TableSidebar
        :schemas="schemas"
        :selected="selectedTable"
        @select="selectedTable = $event"
        @error="showError"
      />
      <main class="content">
        <DataGrid v-if="selectedTable" :table="selectedTable" @error="showError" />
        <div v-else class="welcome-state">
          <div class="welcome-icon">▦</div>
          <h1>{{ loading ? '正在读取数据库…' : '选择一个数据表' }}</h1>
          <p>左侧会列出当前数据库账号有 SELECT 权限的普通表和分区表。</p>
        </div>
      </main>
    </div>

    <Transition name="toast">
      <div v-if="error" class="toast-error">{{ error }}</div>
    </Transition>
  </div>
</template>
