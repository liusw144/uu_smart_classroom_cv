<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { errorMessage, type SchoolApi } from '../api'
const props = defineProps<{ api: SchoolApi }>()
const counts = ref<Record<string, number>>({})
const error = ref('')
onMounted(async () => {
  try {
    counts.value = await props.api.request<Record<string, number>>('GET', '/dashboard')
  } catch (cause) {
    error.value = errorMessage(cause)
  }
})
const cards = [
  { key: 'class_count', label: '班级' },
  { key: 'course_count', label: '课程' },
  { key: 'task_count', label: '课堂任务' },
  { key: 'detection_count', label: '检测作业' },
]
</script>
<template>
  <section class="workspace-panel">
    <h2>工作概览</h2>
    <p>以下数据来自当前账号可访问的真实业务记录。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="metric-grid">
      <article v-for="card in cards" :key="card.key" class="metric">
        <span>{{ card.label }}</span
        ><strong>{{ counts[card.key] ?? '—' }}</strong>
      </article>
    </div>
    <div class="notice-box">
      <h3>视觉能力待接入</h3>
      <p>
        现在可以管理教务、保存图片和追踪作业失败原因。真实检测模型、视频、摄像头和课堂行为趋势尚未完成。
      </p>
    </div>
  </section>
</template>
