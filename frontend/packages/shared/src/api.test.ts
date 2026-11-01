import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios'
import { expect, it } from 'vitest'
import { createApi } from './api'
import { createSession } from './session'

it('失效会话响应会清除令牌', async () => {
  const session = createSession()
  session.setToken('expired-test-token')
  const adapter: AxiosAdapter = async (config) => {
    expect(config.headers.get('Authorization')).toBe('Bearer expired-test-token')
    throw new AxiosError('身份过期', 'ERR_BAD_REQUEST', config, undefined, {
      config,
      data: {},
      headers: new AxiosHeaders(),
      status: 401,
      statusText: 'Unauthorized',
    })
  }
  await expect(createApi(session, adapter).me()).rejects.toBeInstanceOf(AxiosError)
  expect(session.authorization()).toBeUndefined()
})
