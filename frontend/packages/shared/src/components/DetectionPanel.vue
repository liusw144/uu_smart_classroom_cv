<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { errorMessage, loadOptions, type SchoolApi } from '../api'
import DetectionResult from './DetectionResult.vue'
const props = defineProps<{ api: SchoolApi }>()
const tasks = ref<Record<string, unknown>[]>([]),
  models = ref<Record<string, unknown>[]>([])
const task = ref(''),
  model = ref(''),
  file = ref<File>(),
  busy = ref(false),
  error = ref(''),
  message = ref(''),
  jobId = ref('')
const mode = ref('UNAVAILABLE'),
  camera = ref<HTMLVideoElement>(),
  cameraOn = ref(false),
  samples = ref(0)
let stream: MediaStream | undefined,
  disposed = false
onMounted(async () => {
  try {
    const [a, b, capability] = await Promise.all([
      loadOptions(props.api, 'tasks'),
      loadOptions(props.api, 'models'),
      props.api.request('GET', '/capabilities'),
    ])
    tasks.value = a
    mode.value = String(capability.execution_mode)
    models.value = b.filter(
      (item) => item.enabled && (mode.value !== 'EDGE' || item.code === capability.model_version),
    )
    if (models.value.length === 1) model.value = String(models.value[0].id)
  } catch (cause) {
    error.value = errorMessage(cause)
  }
})
function stopCamera() {
  cameraOn.value = false
  stream?.getTracks().forEach((track) => track.stop())
  stream = undefined
}
onUnmounted(() => {
  disposed = true
  stopCamera()
})
async function submitFile(input: File, kind?: string) {
  jobId.value = ''
  message.value = '正在上传素材…'
  const asset = await props.api.upload(input, Number(task.value))
  let job = await props.api.request('POST', '/detections', {
    task_id: Number(task.value),
    model_id: Number(model.value),
    asset_id: asset.id,
    ...(kind ? { media_kind: kind } : {}),
  })
  const id = String(job.id)
  const deadline = Date.now() + 180000
  while (['QUEUED', 'RUNNING'].includes(String(job.status)) && Date.now() < deadline && !disposed) {
    message.value = `作业 ${id}：${job.status === 'QUEUED' ? '等待边缘节点' : '真实推理中'}`
    await new Promise((resolve) => setTimeout(resolve, 1000))
    job = await props.api.request('GET', `/detections/${id}`)
  }
  if (disposed) return
  if (job.status === 'SUCCEEDED') {
    jobId.value = id
    message.value = `作业 ${id}：已完成真实推理`
  } else if (job.status === 'FAILED')
    message.value = `作业已保存：失败。${job.error_code === 'MODEL_UNAVAILABLE' ? '模型未加载，未生成检测结果。' : '失败原因：' + job.error_code}`
  else message.value = `作业状态：${job.status}。可在检测记录中继续查看。`
}
async function submit() {
  if (!file.value) return
  busy.value = true
  error.value = ''
  try {
    await submitFile(file.value)
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function startCamera() {
  error.value = ''
  busy.value = true
  samples.value = 0
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { width: { ideal: 640 }, height: { ideal: 360 } },
      audio: false,
    })
    if (disposed) {
      stopCamera()
      return
    }
    cameraOn.value = true
    await nextTick()
    if (!camera.value) throw new Error('摄像头预览未初始化')
    camera.value.srcObject = stream
    await camera.value.play()
    // 每次采样等待上次作业结束，防止持续摄像头造成无界排队。
    while (cameraOn.value && samples.value < 5 && !disposed) {
      const video = camera.value
      const canvas = document.createElement('canvas')
      canvas.width = Math.min(video.videoWidth, 1280)
      canvas.height = Math.round((video.videoHeight * canvas.width) / video.videoWidth)
      canvas.getContext('2d')?.drawImage(video, 0, 0, canvas.width, canvas.height)
      const blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob(
          (value) => (value ? resolve(value) : reject(new Error('摄像头采样失败'))),
          'image/png',
        ),
      )
      if (!cameraOn.value) break
      samples.value++
      await submitFile(
        new File([blob], `camera-${samples.value}.png`, { type: 'image/png' }),
        'CAMERA',
      )
    }
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    stopCamera()
    busy.value = false
  }
}
</script>
<template>
  <section class="workspace-panel">
    <h2>视觉检测</h2>
    <p>
      图片支持PNG/JPEG，最多10MB；视频支持MP4，最多32MB、30秒。每250毫秒采样，最多32帧。摄像头每轮最多5帧，逐帧执行作业。
    </p>
    <p>
      {{
        mode === 'EDGE'
          ? '当前通过边缘节点执行锁定的YOLOX与RTMPose模型。'
          : '当前真实推理未启用，提交会记录模型不可用。'
      }}
    </p>
    <form class="resource-form" @submit.prevent="submit">
      <label
        >课堂任务<select v-model="task" required aria-label="课堂任务" :disabled="busy">
          <option value="">请选择</option>
          <option v-for="item in tasks" :key="String(item.id)" :value="String(item.id)">
            {{ item.name }}
          </option>
        </select></label
      >
      <label
        >模型档案<select v-model="model" required aria-label="模型档案" :disabled="busy">
          <option value="">请选择</option>
          <option v-for="item in models" :key="String(item.id)" :value="String(item.id)">
            {{ item.name }}（{{ item.code }}）
          </option>
        </select></label
      >
      <label
        >图片或视频<input
          type="file"
          accept="image/png,image/jpeg,video/mp4"
          required
          :disabled="busy"
          @change="file = ($event.target as HTMLInputElement).files?.[0]"
      /></label>
      <button :disabled="busy">{{ busy ? '正在处理…' : '提交视觉作业' }}</button>
    </form>
    <div class="actions">
      <button :disabled="busy || !task || !model || mode !== 'EDGE'" @click="startCamera">
        启动摄像头采样</button
      ><button v-if="cameraOn" class="secondary" @click="stopCamera">停止摄像头</button>
    </div>
    <p>只有点击启动后才请求摄像头权限；公网部署需要HTTPS。自动验收使用公开视频虚拟摄像头。</p>
    <video
      v-if="cameraOn"
      ref="camera"
      muted
      playsinline
      class="asset-preview"
      data-testid="camera-preview"
    />
    <p v-if="samples">本轮摄像头已采样 {{ samples }} 帧</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice-box" role="status">{{ message }}</p>
    <DetectionResult v-if="jobId" :api="api" :job-id="jobId" />
  </section>
</template>
