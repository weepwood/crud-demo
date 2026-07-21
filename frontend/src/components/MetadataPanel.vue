<script setup lang="ts">
import type { TableMetadata } from '../types'

defineProps<{ metadata: TableMetadata }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <div class="drawer-backdrop" @click.self="$emit('close')">
    <aside class="metadata-drawer">
      <header>
        <div>
          <p class="eyebrow">TABLE METADATA</p>
          <h2>{{ metadata.schema }}.{{ metadata.table }}</h2>
        </div>
        <button class="icon-button" @click="$emit('close')">×</button>
      </header>

      <div class="permission-row">
        <span :class="{ enabled: metadata.canInsert }">INSERT</span>
        <span :class="{ enabled: metadata.canUpdate }">UPDATE</span>
        <span :class="{ enabled: metadata.canDelete }">DELETE</span>
      </div>

      <div v-if="metadata.primaryKeys.length" class="metadata-summary">
        主键：<code>{{ metadata.primaryKeys.join(', ') }}</code>
      </div>
      <div v-else class="metadata-summary warning">
        此表没有主键。修改和删除依赖 PostgreSQL ctid，只适合当前页面快照。
      </div>

      <div class="column-list">
        <article v-for="column in metadata.columns" :key="column.name" class="column-card">
          <div class="column-title">
            <strong>{{ column.name }}</strong>
            <span v-if="column.primaryKey" class="key-badge">PK</span>
          </div>
          <code>{{ column.formattedType }}</code>
          <div class="column-flags">
            <span>{{ column.nullable ? 'NULL' : 'NOT NULL' }}</span>
            <span v-if="column.identity">IDENTITY</span>
            <span v-if="column.generated">GENERATED</span>
          </div>
          <small v-if="column.defaultValue">默认值：{{ column.defaultValue }}</small>
        </article>
      </div>
    </aside>
  </div>
</template>
