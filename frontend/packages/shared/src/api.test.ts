import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios'
import { expect, it } from 'vitest'
import { createApi, loadOptions } from './api'
import { createSession } from './session'

it('关联选择会继续读取第二页，避免丢失较早记录', async () => {
  const adapter: AxiosAdapter = async (config) => ({
    config,
    data: {
      data: {
        items:
          config.params.page === 1
            ? Array.from({ length: 100 }, (_, index) => ({
                id: index + 1,
                name: `选项${index + 1}`,
              }))
            : [{ id: 101, name: '较早记录' }],
        total: 101,
        page: config.params.page,
        size: 100,
      },
    },
    headers: new AxiosHeaders(),
    status: 200,
    statusText: 'OK',
  })
  const rows = await loadOptions(createApi(createSession(), adapter), 'classes')
  expect(rows).toHaveLength(101)
  expect(rows[100]).toEqual({ id: 101, name: '较早记录' })
})

it('业务请求携带会话并返回服务端数据', async () => {
  const session = createSession()
  session.setToken('business-test-token')
  const adapter: AxiosAdapter = async (config) => {
    expect(config.url).toBe('/classes')
    expect(config.method).toBe('post')
    expect(config.headers.get('Authorization')).toBe('Bearer business-test-token')
    expect(JSON.parse(config.data)).toEqual({ name: '验证班级' })
    return {
      config,
      data: { data: { id: 12, name: '验证班级' } },
      headers: new AxiosHeaders(),
      status: 200,
      statusText: 'OK',
    }
  }
  await expect(
    createApi(session, adapter).request('POST', '/classes', { name: '验证班级' }),
  ).resolves.toEqual({ id: 12, name: '验证班级' })
})

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
