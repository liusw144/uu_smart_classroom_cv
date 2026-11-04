<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { errorMessage, type PageData, type SchoolApi } from '../api'
import { displayValue } from '../features/resources'

interface Snapshot {
  course_name: string
  class_name: string
  grade: string
  student_count: number
  term: string
  owner_name: string
  captured_at: string
  source: string
}
interface Lesson {
  id: number
  name: string
  status: string
  room_name: string
  version: number
  metadata_locked: boolean
  scheduled_at: string | null
  scheduled_end_at: string | null
  actual_started_at: string | null
  actual_ended_at: string | null
  archived_at: string | null
  snapshot: Snapshot
}
const props = defineProps<{ api: SchoolApi; taskId: string }>()
const emit = defineEmits<{ changed: [] }>()
const lesson = ref<Lesson>()
const events = ref<Record<string, unknown>[]>([])
const page = ref(1),
  total = ref(0),
  busy = ref(false),
  error = ref(''),
  time = ref('')
let generation = 0
const actions: Record<string, string> = {
  CREATED: '创建课堂',
  UPDATED: '修改计划',
  START: '开始课堂',
  COMPLETE: '结束课堂',
  ARCHIVE: '归档课堂',
}

async function load() {
  const current = ++generation
  busy.value = true
  error.value = ''
  try {
    const [record, history] = await Promise.all([
      props.api.request<Lesson>('GET', `/tasks/${props.taskId}`),
      props.api.request<PageData>('GET', `/tasks/${props.taskId}/events`, undefined, {
        page: page.value,
        size: 10,
      }),
    ])
    if (current !== generation) return
    if (!record.snapshot || typeof record.version !== 'number') {
      lesson.value = undefined
      throw new Error('当前业务服务尚未提供课堂快照，请先升级后端并完成数据库迁移')
    }
    lesson.value = record
    events.value = history.items
    total.value = history.total
  } catch (cause) {
    if (current === generation) error.value = errorMessage(cause)
  } finally {
    if (current === generation) busy.value = false
  }
}

async function transition(action: string) {
  const record = lesson.value
  if (!record || busy.value) return
  if (action === 'ARCHIVE' && !window.confirm('归档后课堂只读，不能新增检测和报告。确认归档？'))
    return
  if (record.status === 'LEGACY' && action === 'START' && !time.value) {
    error.value = '历史课堂请先填写真实开始时间'
    return
  }
  const current = generation
  busy.value = true
  error.value = ''
  try {
    await props.api.request('POST', `/tasks/${record.id}/transition`, {
      action,
      version: record.version,
      ...(action !== 'ARCHIVE' && time.value
        ? { occurred_at: new Date(time.value).toISOString() }
        : {}),
    })
    if (current !== generation) return
    time.value = ''
    page.value = 1
    emit('changed')
    await load()
  } catch (cause) {
    if (current === generation) error.value = errorMessage(cause)
  } finally {
    if (current === generation) busy.value = false
  }
}

async function changePage(next: number) {
  page.value = next
  await load()
}
onMounted(load)
watch(
  () => props.taskId,
  () => {
    lesson.value = undefined
    events.value = []
    page.value = 1
    time.value = ''
    void load()
  },
)
onUnmounted(() => {
  generation++
})
</script>

<template>
  <section data-testid="lesson-history">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="lesson">
      <h4>{{ lesson.name }} · {{ displayValue(lesson.status) }}</h4>
      <p>课堂状态记录实际授课过程，检测作业另有独立状态。版本：{{ lesson.version }}</p>
      <p v-if="lesson.snapshot.source === 'LEGACY_BACKFILL'" class="notice-box">
        历史资料在升级时初始化，无法保证代表当时的原始教务信息；确认实际时间后可补记课堂过程。
      </p>
      <dl>
        <dt>班级快照</dt>
        <dd>{{ lesson.snapshot.class_name }}（{{ lesson.snapshot.grade }}）</dd>
        <dt>课程与学期</dt>
        <dd>{{ lesson.snapshot.course_name }} · {{ lesson.snapshot.term }}</dd>
        <dt>负责人快照</dt>
        <dd>{{ lesson.snapshot.owner_name }}</dd>
        <dt>教务人数快照</dt>
        <dd>{{ lesson.snapshot.student_count }}（教务录入值）</dd>
        <dt>快照记录时间</dt>
        <dd>{{ displayValue(lesson.snapshot.captured_at) }}</dd>
        <dt>教室</dt>
        <dd>{{ lesson.room_name || '未填写' }}</dd>
        <dt>计划起止</dt>
        <dd>
          {{ displayValue(lesson.scheduled_at) }} — {{ displayValue(lesson.scheduled_end_at) }}
        </dd>
        <dt>实际起止</dt>
        <dd>
          {{ displayValue(lesson.actual_started_at) }} — {{ displayValue(lesson.actual_ended_at) }}
        </dd>
      </dl>
      <p v-if="lesson.metadata_locked">课堂关键资料已锁定；教务目录后续修改不会刷新本课堂快照。</p>
      <p v-if="lesson.status === 'ARCHIVED'">
        归档时间：{{ displayValue(lesson.archived_at) }}。历史记录可继续查询。
      </p>
      <div v-else class="actions">
        <label v-if="['PLANNED', 'LEGACY', 'IN_PROGRESS'].includes(lesson.status)"
          >实际操作时间（选填，不填使用当前时间）<input
            v-model="time"
            type="datetime-local"
            :disabled="busy"
        /></label>
        <button
          v-if="['PLANNED', 'LEGACY'].includes(lesson.status)"
          :disabled="busy"
          @click="transition('START')"
        >
          开始课堂
        </button>
        <button
          v-if="lesson.status === 'IN_PROGRESS'"
          :disabled="busy"
          @click="transition('COMPLETE')"
        >
          结束课堂
        </button>
        <button
          v-if="['COMPLETED', 'LEGACY'].includes(lesson.status)"
          :disabled="busy"
          @click="transition('ARCHIVE')"
        >
          归档课堂
        </button>
      </div>
      <h4>课堂变更记录</h4>
      <p v-if="!events.length">暂无记录；历史初始化不代表用户曾执行过相应操作。</p>
      <ol>
        <li v-for="item in events" :key="String(item.id)">
          {{ displayValue(item.occurred_at) }} · {{ item.actor_name }} ·
          {{ actions[String(item.action)] || item.action }} · 版本 {{ item.version }} ·
          {{ item.note }}
        </li>
      </ol>
      <div class="pagination">
        <button :disabled="busy || page <= 1" @click="changePage(page - 1)">上一页记录</button>
        <span>共 {{ total }} 条，第 {{ page }} 页</span>
        <button :disabled="busy || page * 10 >= total" @click="changePage(page + 1)">
          下一页记录
        </button>
        <button :disabled="busy" @click="load">刷新课堂</button>
      </div>
    </template>
  </section>
</template>
