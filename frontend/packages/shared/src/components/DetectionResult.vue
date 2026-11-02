<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { errorMessage, type SchoolApi } from '../api'
const props = defineProps<{ api: SchoolApi; jobId: string }>()
interface Result {
  model_version: string
  sampled_frames: number
  person_instances: number
  pose_counts: { HAND_UP_CANDIDATE: number }
  artifact_content_type: string
  elapsed_ms: number
}
const result = ref<Result>(),
  preview = ref(''),
  error = ref(''),
  report = ref(''),
  busy = ref(false)
let generation = 0
async function load() {
  const current = ++generation
  busy.value = false
  error.value = ''
  report.value = ''
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  result.value = undefined
  try {
    const data = await props.api.request<Result>('GET', `/detections/${props.jobId}/result`)
    const blob = await props.api.artifact(props.jobId)
    if (current !== generation) return
    result.value = data
    preview.value = URL.createObjectURL(blob)
  } catch (cause) {
    if (current === generation) error.value = errorMessage(cause)
  }
}
async function analyze() {
  const current = generation
  busy.value = true
  error.value = ''
  try {
    const data = await props.api.request('POST', `/detections/${props.jobId}/analysis`)
    if (current === generation) report.value = String(data.content)
  } catch (cause) {
    if (current === generation) error.value = errorMessage(cause)
  } finally {
    if (current === generation) busy.value = false
  }
}
onMounted(load)
watch(() => props.jobId, load)
onUnmounted(() => {
  generation++
  if (preview.value) URL.revokeObjectURL(preview.value)
})
</script>
<template>
  <section class="record-detail" data-testid="detection-result">
    <h3>真实推理结果</h3>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="result">
      <p>
        模型：{{ result.model_version }}；采样 {{ result.sampled_frames }} 帧；累计检出
        {{ result.person_instances }} 人次；抬手姿态候选
        {{ result.pose_counts.HAND_UP_CANDIDATE }} 人次。
      </p>
      <p>累计人次不是独立人数。通用模型及候选规则未经课堂标注评测，不能据此评价学生或教师。</p>
      <video
        v-if="result.artifact_content_type === 'video/mp4' && preview"
        :src="preview"
        controls
        class="asset-preview"
      />
      <img
        v-else-if="preview"
        :src="preview"
        class="asset-preview"
        alt="实际模型生成的人物框与17点姿态"
      />
      <button :disabled="busy" @click="analyze">
        {{ busy ? '本地AI分析中…' : '生成本地AI分析' }}
      </button>
      <pre v-if="report" data-testid="ai-report">{{ report }}</pre>
    </template>
  </section>
</template>
