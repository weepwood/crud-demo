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
    <TableSidebar
      :schemas="schemas"
      :selected="selectedTable"
      @select="selectedTable = $event"
      @error="showError"
      @logout="logout"
    />

    <main class="content-shell">
      <header class="page-intro">
        <div class="intro-identity">
          <div class="intro-avatar">DB</div>
          <div>
            <p class="eyebrow">POSTGRESQL WORKSPACE</p>
            <h1>欢迎使用数据库控制台</h1>
            <p>浏览数据库对象、检查字段结构，并安全地管理表中数据。</p>
          </div>
        </div>
        <div class="connection-chip">
          <span class="status-dot" />
          PostgreSQL 已连接
        </div>
      </header>

      <DataGrid v-if="selectedTable" :table="selectedTable" @error="showError" />

      <section v-else class="overview-page">
        <div class="overview-grid">
          <article class="overview-card overview-card-primary">
            <div>
              <p class="eyebrow">DATA CATALOG</p>
              <h2>{{ loading ? '正在读取数据库…' : '选择一个数据表' }}</h2>
              <p>左侧目录会列出当前数据库账号拥有 SELECT 权限的普通表和分区表。</p>
            </div>
            <div class="overview-illustration" aria-hidden="true">
              <span class="illustration-card illustration-card-main">TABLE</span>
              <span class="illustration-line illustration-line-one" />
              <span class="illustration-line illustration-line-two" />
              <span class="illustration-node">+</span>
            </div>
          </article>

          <article class="overview-card">
            <div class="overview-card-icon">⌕</div>
            <h2>浏览与搜索</h2>
            <p>对当前表执行分页浏览、全字段搜索和字段排序。</p>
          </article>

          <article class="overview-card">
            <div class="overview-card-icon">✦</div>
            <h2>动态 CRUD</h2>
            <p>根据 PostgreSQL 元数据生成新增、编辑和删除界面。</p>
          </article>

          <article class="overview-card overview-card-wide">
            <div class="overview-card-icon">◇</div>
            <div>
              <h2>权限与安全边界</h2>
              <p>写入能力由数据库账号权限、Schema 白名单和全局只读模式共同决定。</p>
            </div>
            <span class="overview-badge">Metadata driven</span>
          </article>
        </div>
      </section>
    </main>

    <Transition name="toast">
      <div v-if="error" class="toast-error">{{ error }}</div>
    </Transition>
  </div>
</template>
