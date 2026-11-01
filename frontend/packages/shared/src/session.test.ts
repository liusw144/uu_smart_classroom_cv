import { describe, expect, it } from 'vitest'
import { createSession } from './session'

describe('会话生命周期', () => {
  it('退出后不再发送原访问令牌', () => {
    const session = createSession()
    session.setToken('test-token')
    expect(session.authorization()).toBe('Bearer test-token')
    session.clear()
    expect(session.authorization()).toBeUndefined()
  })
  it('不同应用实例不共享会话', () => {
    const teacher = createSession()
    const admin = createSession()
    teacher.setToken('test-token')
    expect(admin.authorization()).toBeUndefined()
  })
})
