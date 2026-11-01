<script setup lang="ts">
import { ref } from 'vue'
import { createApi } from './api'
import type { User } from './api'
import { createSession } from './session'

const props = defineProps<{ audience: 'teacher' | 'admin' }>()
const session = createSession()
const api = createApi(session)
const school = ref('')
const username = ref('')
const password = ref('')
const user = ref<User>()
const busy = ref(false)
const error = ref('')

async function login() {
  busy.value = true
  error.value = ''
  try {
    const authenticated = await api.login({
      school_code: school.value,
      username: username.value,
      password: password.value,
    })
    if (props.audience === 'admin' && authenticated.role === 'TEACHER') {
      await api.logout()
      throw new Error('请使用教师端登录')
    }
    user.value = authenticated
  } catch {
    user.value = undefined
    session.clear()
    error.value = '登录未成功，请检查学校、账号、密码及入口，并确认业务服务可用。'
  } finally {
    password.value = ''
    busy.value = false
  }
}

async function logout() {
  busy.value = true
  try {
    await api.logout()
  } catch {
    error.value = '本地会话已清除，远端注销未确认，令牌将在服务端到期。'
  } finally {
    user.value = undefined
    busy.value = false
  }
}
</script>

<template>
  <main class="shell">
    <header><span class="brand">慧课 · UU</span><span class="stage">工程基础</span></header>
    <section class="intro">
      <p class="eyebrow">课堂行为检测与分析</p>
      <h1>{{ audience === 'teacher' ? '教师工作台' : '学校管理台' }}</h1>
      <p>围绕课堂任务，连接教学数据与校内视觉分析。</p>
    </section>
    <section v-if="!user" class="panel">
      <h2>登录学校空间</h2>
      <form @submit.prevent="login">
        <label for="school">学校代码</label
        ><input
          id="school"
          v-model.trim="school"
          autocomplete="organization"
          maxlength="64"
          required
        />
        <label for="username">账号</label
        ><input
          id="username"
          v-model.trim="username"
          autocomplete="username"
          maxlength="64"
          required
        />
        <label for="password">密码</label
        ><input
          id="password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          maxlength="72"
          required
        />
        <button :disabled="busy" type="submit">{{ busy ? '正在登录…' : '登录' }}</button>
      </form>
    </section>
    <section v-else class="panel">
      <h2>{{ user.username }}，已连接学校空间</h2>
      <dl>
        <dt>学校编号</dt>
        <dd>{{ user.tenant_id }}</dd>
        <dt>角色</dt>
        <dd>{{ user.role }}</dd>
      </dl>
      <p>登录基础已接入；课堂任务与视觉分析功能将在后续阶段交付。</p>
      <button :disabled="busy" @click="logout">退出登录</button>
    </section>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <footer>当前版本为工程基础。尚未接入真实检测模型，不能用于正式课堂分析。</footer>
  </main>
</template>
