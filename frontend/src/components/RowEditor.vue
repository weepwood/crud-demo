<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { ColumnInfo, TableMetadata } from '../types'

const props = defineProps<{
  mode: 'create' | 'edit'
  metadata: TableMetadata
  row?: Record<string, unknown> | null
}>()

const emit = defineEmits<{
  close: []
  submit: [values: Record<string, unknown>]
}>()

const submitting = ref(false)
const localError = ref('')
const active = reactive(new Set<string>())
const nullColumns = reactive(new Set<string>())
const form = reactive<Record<string, string>>({})

for (const column of props.metadata.columns) {
  if (!isEditable(column)) continue
  const value = props.row?.[column.name]
  if (props.mode === 'edit') {
    active.add(column.name)
    if (value === null || value === undefined) {
      nullColumns.add(column.name)
      form[column.name] = ''
    } else {
      form[column.name] = formatInputValue(value, column)
    }
  } else if (!column.nullable && !column.defaultValue) {
    active.add(column.name)
    form[column.name] = defaultInputValue(column)
  } else {
    form[column.name] = defaultInputValue(column)
  }
}

const editableColumns = computed(() => props.metadata.columns.filter(isEditable))

function isEditable(column: ColumnInfo): boolean {
  return !column.identity && !column.generated
}

function defaultInputValue(column: ColumnInfo): string {
  return column.udtName === 'bool' ? 'false' : ''
}

function formatInputValue(value: unknown, column: ColumnInfo): string {
  if (column.udtName === 'bool') return value ? 'true' : 'false'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

function inputType(column: ColumnInfo): string {
  if (column.udtName === 'date') return 'date'
  if (column.udtName.includes('timestamp')) return 'datetime-local'
  if (column.udtName === 'time') return 'time'
  if (['int2', 'int4', 'int8', 'float4', 'float8', 'numeric'].includes(column.udtName)) return 'number'
  return 'text'
}

function isTextarea(column: ColumnInfo): boolean {
  return column.udtName === 'json' || column.udtName === 'jsonb'
    || column.formattedType.includes('text') || column.typeCategory === 'A'
}

function markActive(name: string) {
  active.add(name)
  nullColumns.delete(name)
}

function toggleNull(column: ColumnInfo, checked: boolean) {
  active.add(column.name)
  if (checked) nullColumns.add(column.name)
  else nullColumns.delete(column.name)
}

function parseValue(column: ColumnInfo): unknown {
  if (nullColumns.has(column.name)) return null
  const value = form[column.name]
  if (column.udtName === 'bool') return value === 'true'
  if (value === '') return ''
  if (['int2', 'int4', 'int8'].includes(column.udtName)) return Number.parseInt(String(value), 10)
  if (['float4', 'float8', 'numeric'].includes(column.udtName)) return Number(String(value))
  if (column.udtName === 'json' || column.udtName === 'jsonb') {
    try {
      return JSON.parse(String(value))
    } catch {
      throw new Error(`${column.name} 不是合法 JSON`)
    }
  }
  return value
}

async function submit() {
  localError.value = ''
  const values: Record<string, unknown> = {}
  try {
    for (const column of editableColumns.value) {
      if (props.mode === 'create' && !active.has(column.name)) continue
      values[column.name] = parseValue(column)
    }
    submitting.value = true
    emit('submit', values)
  } catch (cause) {
    localError.value = cause instanceof Error ? cause.message : '表单数据无效'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="modal-backdrop" @click.self="$emit('close')">
    <section class="row-editor">
      <header>
        <div>
          <p class="eyebrow">{{ mode === 'create' ? 'NEW ROW' : 'EDIT ROW' }}</p>
          <h2>{{ metadata.schema }}.{{ metadata.table }}</h2>
        </div>
        <button class="icon-button" @click="$emit('close')">×</button>
      </header>

      <p v-if="mode === 'create'" class="editor-tip">
        可选字段留空且不激活时，将由数据库默认值处理。数组等高级类型可填写 PostgreSQL 字面量。
      </p>

      <form class="field-grid" @submit.prevent="submit">
        <label v-for="column in editableColumns" :key="column.name" class="field-card">
          <div class="field-heading">
            <span>
              {{ column.name }}
              <b v-if="column.primaryKey">PK</b>
            </span>
            <code>{{ column.formattedType }}</code>
          </div>

          <select
            v-if="column.udtName === 'bool'"
            v-model="form[column.name]"
            :disabled="nullColumns.has(column.name)"
            @change="markActive(column.name)"
          >
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
          <textarea
            v-else-if="isTextarea(column)"
            v-model="form[column.name]"
            rows="4"
            :disabled="nullColumns.has(column.name)"
            :placeholder="column.defaultValue ? `默认：${column.defaultValue}` : ''"
            @input="markActive(column.name)"
          />
          <input
            v-else
            v-model="form[column.name]"
            :type="inputType(column)"
            :disabled="nullColumns.has(column.name)"
            :placeholder="column.defaultValue ? `默认：${column.defaultValue}` : ''"
            @input="markActive(column.name)"
          />

          <div class="field-options">
            <label v-if="mode === 'create' && (column.nullable || column.defaultValue)" class="inline-check">
              <input
                type="checkbox"
                :checked="active.has(column.name)"
                @change="(event) => (event.target as HTMLInputElement).checked ? active.add(column.name) : active.delete(column.name)"
              />
              提交此字段
            </label>
            <label v-if="column.nullable" class="inline-check">
              <input
                type="checkbox"
                :checked="nullColumns.has(column.name)"
                @change="(event) => toggleNull(column, (event.target as HTMLInputElement).checked)"
              />
              设为 NULL
            </label>
          </div>
        </label>
      </form>

      <p v-if="localError" class="form-error">{{ localError }}</p>
      <footer>
        <button class="secondary" @click="$emit('close')">取消</button>
        <button class="primary" :disabled="submitting" @click="submit">
          {{ mode === 'create' ? '创建记录' : '保存修改' }}
        </button>
      </footer>
    </section>
  </div>
</template>
