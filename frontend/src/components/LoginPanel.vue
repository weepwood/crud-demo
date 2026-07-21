<script setup lang="ts">
import { ref } from 'vue'
import { api, clearCredentials, saveCredentials } from '../api'

const emit = defineEmits<{ authenticated: [] }>()
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function login() {
  error.value = ''
  loading.value = true
  saveCredentials(username.value, password.value)
  try {
    await api.schemas()
    emit('authenticated')
  } catch (cause) {
    clearCredentials()
    error.value = cause instanceof Error ? cause.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand-mark">PG</div>
      <p class="eyebrow">DATABASE CONSOLE</p>
      <h1>PostgreSQL CRUD</h1>
      <p class="description">连接后自动读取数据库元数据，并为所有可访问表生成 CRUD 操作界面。</p>

      <form @submit.prevent="login">
        <label>
          <span>用户名</span>
          <input v-model.trim="username" autocomplete="username" required />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" required autofocus />
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary wide" type="submit" :disabled="loading">
          {{ loading ? '正在连接…' : '进入数据库控制台' }}
        </button>
      </form>

      <p class="login-note">首次使用 Docker 示例环境时，默认账号为 admin，密码由 APP_PASSWORD 配置。</p>
    </section>
  </main>
</template>
