<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { errorMessage, type SchoolApi } from '../api'
const props = defineProps<{ api: SchoolApi }>()
const emit = defineEmits<{ reauthenticate: [] }>()
const name = ref(''),
  email = ref(''),
  avatar = ref<string | null>(null),
  preview = ref(''),
  oldPassword = ref(''),
  newPassword = ref(''),
  error = ref(''),
  message = ref(''),
  busy = ref(false)
async function showAvatar(id: string | null) {
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = id ? URL.createObjectURL(await props.api.asset(id)) : ''
}
onMounted(async () => {
  try {
    const value = await props.api.request('GET', '/profile')
    name.value = String(value.display_name || value.username)
    email.value = String(value.email || '')
    avatar.value = value.avatar_asset_id as string | null
    await showAvatar(avatar.value)
  } catch (cause) {
    error.value = errorMessage(cause)
  }
})
onUnmounted(() => {
  if (preview.value) URL.revokeObjectURL(preview.value)
})
async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  busy.value = true
  try {
    const value = await props.api.upload(file)
    avatar.value = String(value.id)
    await showAvatar(avatar.value)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function save() {
  busy.value = true
  error.value = ''
  try {
    await props.api.request('PUT', '/profile', {
      display_name: name.value,
      email: email.value,
      avatar_asset_id: avatar.value,
    })
    message.value = '资料已保存'
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function changePassword() {
  busy.value = true
  error.value = ''
  try {
    await props.api.request('POST', '/profile/password', {
      old_password: oldPassword.value,
      new_password: newPassword.value,
    })
    emit('reauthenticate')
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    oldPassword.value = ''
    newPassword.value = ''
    busy.value = false
  }
}
async function clearAvatar() {
  avatar.value = null
  await showAvatar(null)
}
</script>
<template>
  <section class="workspace-panel">
    <h2>个人中心</h2>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="success" role="status">{{ message }}</p>
    <form class="resource-form" @submit.prevent="save">
      <h3>个人资料</h3>
      <img v-if="preview" :src="preview" class="avatar" alt="当前头像" />
      <button v-if="avatar" type="button" class="secondary" @click="clearAvatar">
        移除头像（保存后生效）</button
      ><label>头像图片<input type="file" accept="image/png,image/jpeg" @change="upload" /></label
      ><label>显示姓名<input v-model.trim="name" maxlength="128" required /></label
      ><label>电子邮箱<input v-model.trim="email" type="email" maxlength="254" /></label
      ><button :disabled="busy">保存资料</button>
    </form>
    <form class="resource-form" @submit.prevent="changePassword">
      <h3>修改密码</h3>
      <p>新密码至少12字符、UTF-8编码后最多72字节；成功后需要重新登录。</p>
      <label
        >原密码<input
          v-model="oldPassword"
          type="password"
          autocomplete="current-password"
          required
          maxlength="72" /></label
      ><label
        >新密码<input
          v-model="newPassword"
          type="password"
          autocomplete="new-password"
          required
          minlength="12"
          maxlength="72" /></label
      ><button :disabled="busy">修改密码并重新登录</button>
    </form>
  </section>
</template>
