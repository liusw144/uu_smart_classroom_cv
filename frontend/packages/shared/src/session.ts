export function createSession(onClear?: () => void) {
  let token: string | undefined
  return {
    setToken(value: string) {
      token = value
    },
    authorization(): string | undefined {
      return token ? `Bearer ${token}` : undefined
    },
    clear() {
      token = undefined
      onClear?.()
    },
  }
}
