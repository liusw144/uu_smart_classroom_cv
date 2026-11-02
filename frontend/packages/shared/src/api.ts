import axios, { type AxiosAdapter, type Method } from 'axios'
import type { createSession } from './session'

export interface User {
  id: number
  tenant_id: number
  username: string
  role: 'TEACHER' | 'SCHOOL_ADMIN' | 'PLATFORM_OPERATOR'
  permissions: string[]
}

export interface LoginInput {
  school_code: string
  username: string
  password: string
}
interface LoginOutput {
  access_token: string
  expires_in: number
  user: User
}

export function createApi(session: ReturnType<typeof createSession>, adapter?: AxiosAdapter) {
  const http = axios.create({ baseURL: '/api/v1', timeout: 15000, adapter })
  http.interceptors.request.use((config) => {
    const authorization = session.authorization()
    if (authorization) config.headers.set('Authorization', authorization)
    return config
  })
  http.interceptors.response.use(
    (response) => response,
    (error) => {
      if (axios.isAxiosError(error) && error.response?.status === 401) session.clear()
      return Promise.reject(error)
    },
  )
  return {
    async request<T = Record<string, unknown>>(
      method: Method,
      path: string,
      data?: unknown,
      params?: Record<string, unknown>,
    ): Promise<T> {
      return (
        await http.request<{ data: T }>({
          method,
          url: path,
          data,
          params,
          timeout: path.endsWith('/analysis') ? 105000 : 15000,
        })
      ).data.data
    },
    async upload(file: File, taskId?: number): Promise<Record<string, unknown>> {
      const form = new FormData()
      form.set('file', file)
      if (taskId) form.set('task_id', String(taskId))
      return (await http.post('/assets', form)).data.data
    },
    async asset(id: string): Promise<Blob> {
      return (await http.get(`/assets/${encodeURIComponent(id)}`, { responseType: 'blob' })).data
    },
    async artifact(id: string): Promise<Blob> {
      return (
        await http.get(`/detections/${encodeURIComponent(id)}/artifact`, { responseType: 'blob' })
      ).data
    },
    async login(input: LoginInput): Promise<User> {
      const response = await http.post<{ data: LoginOutput }>('/auth/login', input)
      session.setToken(response.data.data.access_token)
      return response.data.data.user
    },
    async me(): Promise<User> {
      return (await http.get<{ data: User }>('/auth/me')).data.data
    },
    async logout() {
      try {
        await http.post('/auth/logout')
      } finally {
        session.clear()
      }
    },
  }
}

export type SchoolApi = ReturnType<typeof createApi>
export async function loadOptions(
  api: SchoolApi,
  resource: string,
): Promise<Record<string, unknown>[]> {
  const result: Record<string, unknown>[] = []
  let page = 1
  while (true) {
    const data = await api.request<PageData>('GET', `/${resource}`, undefined, { page, size: 100 })
    result.push(...data.items)
    if (page * data.size >= data.total || !data.items.length) return result
    page++
  }
}
export interface PageData {
  items: Record<string, unknown>[]
  total: number
  page: number
  size: number
}
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error))
    return error.response?.data?.error?.message || '服务连接失败，请确认本机业务服务可用。'
  return error instanceof Error ? error.message : '操作未完成，请稍后重试。'
}
