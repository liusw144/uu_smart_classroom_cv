import axios, { type AxiosAdapter } from 'axios'
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
