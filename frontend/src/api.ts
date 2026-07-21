import type {
  MutationRequest,
  PageResponse,
  SchemaInfo,
  TableInfo,
  TableMetadata,
} from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const AUTH_KEY = 'crud-demo-basic-auth'

function toBase64(value: string): string {
  const bytes = new TextEncoder().encode(value)
  let binary = ''
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary)
}

export function saveCredentials(username: string, password: string): void {
  sessionStorage.setItem(AUTH_KEY, `Basic ${toBase64(`${username}:${password}`)}`)
}

export function clearCredentials(): void {
  sessionStorage.removeItem(AUTH_KEY)
}

export function hasCredentials(): boolean {
  return Boolean(sessionStorage.getItem(AUTH_KEY))
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const authorization = sessionStorage.getItem(AUTH_KEY)
  const headers = new Headers(init.headers)
  if (authorization) {
    headers.set('Authorization', authorization)
  }
  if (init.body) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers })
  if (response.status === 401) {
    clearCredentials()
    window.dispatchEvent(new CustomEvent('crud-demo:unauthorized'))
    throw new Error('登录信息无效，请重新登录')
  }

  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(payload?.message || `请求失败（${response.status}）`)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

function encodePath(value: string): string {
  return encodeURIComponent(value)
}

export const api = {
  schemas: () => request<SchemaInfo[]>('/api/meta/schemas'),

  tables: (schema: string) =>
    request<TableInfo[]>(`/api/meta/${encodePath(schema)}/tables`),

  metadata: (schema: string, table: string) =>
    request<TableMetadata>(`/api/meta/${encodePath(schema)}/${encodePath(table)}`),

  rows: (
    schema: string,
    table: string,
    options: { page: number; size: number; q?: string; sort?: string; direction?: string },
  ) => {
    const params = new URLSearchParams({
      page: String(options.page),
      size: String(options.size),
      direction: options.direction ?? 'asc',
    })
    if (options.q) params.set('q', options.q)
    if (options.sort) params.set('sort', options.sort)
    return request<PageResponse>(
      `/api/data/${encodePath(schema)}/${encodePath(table)}?${params.toString()}`,
    )
  },

  create: (schema: string, table: string, body: MutationRequest) =>
    request<Record<string, unknown>>(
      `/api/data/${encodePath(schema)}/${encodePath(table)}`,
      { method: 'POST', body: JSON.stringify(body) },
    ),

  update: (schema: string, table: string, body: MutationRequest) =>
    request<Record<string, unknown>>(
      `/api/data/${encodePath(schema)}/${encodePath(table)}`,
      { method: 'PUT', body: JSON.stringify(body) },
    ),

  remove: (schema: string, table: string, body: MutationRequest) =>
    request<Record<string, unknown>>(
      `/api/data/${encodePath(schema)}/${encodePath(table)}`,
      { method: 'DELETE', body: JSON.stringify(body) },
    ),
}
