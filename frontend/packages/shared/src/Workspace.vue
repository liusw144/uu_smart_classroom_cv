<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SchoolApi, User } from './api'
import { resources } from './features/resources'
import ResourceManager from './components/ResourceManager.vue'
import Dashboard from './components/Dashboard.vue'
import ProfilePanel from './components/ProfilePanel.vue'
import DetectionPanel from './components/DetectionPanel.vue'
const props = defineProps<{ api: SchoolApi; user: User; audience: 'teacher' | 'admin' }>()
defineEmits<{ reauthenticate: [] }>()
const active = ref('dashboard')
const admin = computed(() => props.user.role === 'SCHOOL_ADMIN' && props.audience === 'admin')
const navigation = computed(() => [
  { key: 'dashboard', title: '工作概览' },
  ...['classes', 'courses', 'tasks', 'assets'].map((key) => resources[key]),
  { key: 'image', title: '视觉检测' },
  ...['detections', 'reports', 'notices'].map((key) => resources[key]),
  ...(admin.value ? ['users', 'models', 'categories', 'logs'].map((key) => resources[key]) : []),
  { key: 'profile', title: '个人中心' },
])
</script>
<template>
  <div class="workspace">
    <nav aria-label="业务导航">
      <button
        v-for="item in navigation"
        :key="item.key"
        :class="{ active: active === item.key }"
        @click="active = item.key"
      >
        {{ item.title }}
      </button>
    </nav>
    <Dashboard v-if="active === 'dashboard'" :api="api" /><ProfilePanel
      v-else-if="active === 'profile'"
      :api="api"
      @reauthenticate="$emit('reauthenticate')"
    /><DetectionPanel v-else-if="active === 'image'" :api="api" /><ResourceManager
      v-else
      :key="active"
      :api="api"
      :resource="resources[active]"
      :admin="admin"
    />
  </div>
</template>
