<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { errorMessage, loadOptions, type PageData, type SchoolApi } from '../api'
import { displayValue, type Resource } from '../features/resources'
import DetectionResult from './DetectionResult.vue'
import LessonDetail from './LessonDetail.vue'
const props = defineProps<{ api: SchoolApi; resource: Resource; admin: boolean }>()
const rows = ref<Record<string, unknown>[]>([])
const selected = ref<Record<string, unknown>>()
const form = reactive<Record<string, string | number | boolean>>({})
const references = reactive<Record<string, Record<string, unknown>[]>>({})
const editing = ref<string>()
const editVersion = ref<number>()
const formVisible = ref(false)
const query = ref('')
const status = ref('')
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const error = ref('')
const message = ref('')
const preview = ref('')
onUnmounted(() => {
  if (preview.value) URL.revokeObjectURL(preview.value)
})
async function inspect(row: Record<string, unknown>) {
  selected.value = row
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  if (props.resource.key === 'assets') {
    try {
      preview.value = URL.createObjectURL(await props.api.asset(String(row.id)))
    } catch (cause) {
      error.value = errorMessage(cause)
    }
  }
}
function labelFor(key: string) {
  return (
    props.resource.columns.find((column) => column.key === key)?.label ||
    (
      {
        id: '编号',
        tenant_id: '学校编号',
        owner_id: '负责人编号',
        content: '正文',
        description: '说明',
        asset_id: '素材编号',
        task_id: '课堂任务编号',
        model_id: '模型档案编号',
        sha256: '内容校验值',
        request_id: '请求编号',
        display_name: '姓名',
        email: '电子邮箱',
        avatar_asset_id: '头像素材编号',
        created_at: '时间',
        name: '名称',
        code: '代码',
        enabled: '启用',
        published: '发布状态',
        scheduled_at: '计划时间',
        class_id: '班级编号',
        course_id: '课程编号',
        term: '学期',
        grade: '年级',
        student_count: '教务人数',
      } as Record<string, string>
    )[key] ||
    '记录信息'
  )
}
const mayWrite = computed(
  () =>
    props.admin ||
    ['classes', 'courses', 'tasks', 'reports', 'detections', 'assets'].includes(props.resource.key),
)
const fields = computed(() =>
  props.resource.fields.filter(
    (field) =>
      (!field.createOnly || !editing.value) &&
      (!field.editOnly || !!editing.value) &&
      (field.key !== 'owner_id' || props.admin),
  ),
)
async function load() {
  busy.value = true
  error.value = ''
  try {
    const result = await props.api.request<PageData>('GET', `/${props.resource.key}`, undefined, {
      q: query.value,
      page: page.value,
      size: 10,
      ...(props.resource.key === 'detections' ? { status: status.value } : {}),
    })
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function open(row?: Record<string, unknown>) {
  editing.value = row ? String(row.id) : undefined
  editVersion.value = typeof row?.version === 'number' ? row.version : undefined
  for (const key of Object.keys(form)) delete form[key]
  for (const field of props.resource.fields)
    form[field.key] =
      (row?.[field.key] as string | number | boolean) ??
      (field.type === 'checkbox' ? true : field.key === 'role' ? 'TEACHER' : '')
  for (const field of props.resource.fields) {
    if (field.type === 'datetime-local' && form[field.key]) {
      const date = new Date(String(form[field.key]))
      form[field.key] = new Date(date.getTime() - date.getTimezoneOffset() * 60000)
        .toISOString()
        .slice(0, 16)
    }
  }
  formVisible.value = true
  message.value = ''
  error.value = ''
  try {
    for (const field of fields.value)
      if (field.reference)
        references[field.reference] = (await loadOptions(props.api, field.reference)).filter(
          (item) =>
            item.enabled !== false && (field.reference !== 'tasks' || item.status !== 'ARCHIVED'),
        )
  } catch (cause) {
    error.value = errorMessage(cause)
  }
}
async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const input: Record<string, unknown> = {}
    if (props.resource.key === 'tasks' && editing.value) {
      if (editVersion.value === undefined)
        throw new Error('课堂缺少版本信息，请升级后端并刷新后重试')
      input.version = editVersion.value
    }
    for (const field of fields.value) {
      const value = form[field.key]
      if (field.reference) {
        if (value !== '') input[field.key] = Number(value)
      } else if (field.type === 'datetime-local')
        input[field.key] = value ? new Date(String(value)).toISOString() : null
      else input[field.key] = value
    }
    const result = await props.api.request(
      editing.value ? 'PUT' : 'POST',
      `/${props.resource.key}${editing.value ? `/${editing.value}` : ''}`,
      input,
    )
    formVisible.value = false
    selected.value = props.resource.key === 'reports' ? result : undefined
    message.value = '已保存'
    await load()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
async function remove(row: Record<string, unknown>) {
  if (!window.confirm(`确认删除这条${props.resource.singular}？存在引用时将拒绝删除。`)) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    if (['detections', 'reports'].includes(props.resource.key)) {
      const task = await props.api.request('GET', `/tasks/${row.task_id}`)
      if (task.status === 'ARCHIVED')
        throw new Error('所属课堂已归档，检测结果和报告保留为只读历史')
    }
    await props.api.request('DELETE', `/${props.resource.key}/${row.id}`)
    selected.value = undefined
    message.value = '已删除'
    await load()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
function printReport() {
  window.print()
}
function search() {
  page.value = 1
  return load()
}
function previousPage() {
  page.value--
  return load()
}
function nextPage() {
  page.value++
  return load()
}
onMounted(load)
</script>

<template>
  <section class="workspace-panel">
    <div class="section-heading">
      <h2>{{ resource.title }}</h2>
      <button v-if="mayWrite && resource.fields.length" :disabled="busy" @click="open()">
        新增{{ resource.singular }}
      </button>
    </div>
    <p v-if="resource.hint" class="muted">{{ resource.hint }}</p>
    <form class="toolbar" @submit.prevent="search">
      <label>搜索<input v-model="query" maxlength="128" placeholder="输入名称或关键词" /></label
      ><label v-if="resource.key === 'detections'"
        >检测状态<select v-model="status" aria-label="检测状态">
          <option value="">全部</option>
          <option value="FAILED">失败</option>
          <option value="RUNNING">处理中</option>
          <option value="QUEUED">等待节点</option>
          <option value="CANCELLED">已取消</option>
          <option value="SUCCEEDED">成功</option>
        </select></label
      ><button :disabled="busy">查询</button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="success" role="status">{{ message }}</p>
    <form
      v-if="formVisible"
      class="resource-form"
      data-testid="resource-form"
      @submit.prevent="save"
    >
      <h3>{{ editing ? '编辑' : '新增' }}{{ resource.singular }}</h3>
      <label v-for="field in fields" :key="field.key" :for="`field-${field.key}`"
        >{{ field.label }}
        <select
          v-if="field.reference"
          :aria-label="field.label"
          :id="`field-${field.key}`"
          v-model="form[field.key] as string"
          :required="field.required"
        >
          <option value="">请选择</option>
          <option
            v-for="item in references[field.reference] || []"
            :key="String(item.id)"
            :value="item.id as number"
          >
            {{ item.name || item.display_name || item.username }}（{{ item.id }}）
          </option>
        </select>
        <select
          v-else-if="field.options"
          :aria-label="field.label"
          :id="`field-${field.key}`"
          v-model="form[field.key]"
        >
          <option v-for="option in field.options" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <textarea
          v-else-if="field.type === 'textarea'"
          :id="`field-${field.key}`"
          v-model="form[field.key] as string"
          :maxlength="field.max"
          :required="field.required"
          rows="4"
        />
        <input
          v-else-if="field.type === 'checkbox'"
          :id="`field-${field.key}`"
          v-model="form[field.key]"
          type="checkbox"
        />
        <input
          v-else-if="field.type === 'number'"
          :id="`field-${field.key}`"
          v-model.number="form[field.key]"
          type="number"
          min="0"
          :max="field.max"
          :required="field.required"
        />
        <input
          v-else
          :id="`field-${field.key}`"
          v-model="form[field.key]"
          :type="field.type || 'text'"
          :maxlength="field.max"
          :required="field.required"
          :autocomplete="field.type === 'password' ? 'new-password' : 'off'"
        />
      </label>
      <div class="actions">
        <button :disabled="busy" type="submit">保存</button
        ><button class="secondary" type="button" @click="formVisible = false">取消</button>
      </div>
    </form>
    <div class="table-scroll">
      <table>
        <thead>
          <tr>
            <th v-for="column in resource.columns" :key="column.key">{{ column.label }}</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="String(row.id)" :data-record-id="String(row.id)">
            <td v-for="column in resource.columns" :key="column.key">
              {{ displayValue(row[column.key]) }}
            </td>
            <td class="row-actions">
              <button class="secondary" @click="inspect(row)">查看</button
              ><button
                v-if="
                  mayWrite &&
                  resource.editable &&
                  (resource.key !== 'tasks' || !row.metadata_locked)
                "
                class="secondary"
                @click="open(row)"
              >
                编辑</button
              ><button
                v-if="mayWrite && resource.deletable"
                class="danger"
                :disabled="
                  busy ||
                  (resource.key === 'tasks' && (row.status !== 'PLANNED' || !!row.metadata_locked))
                "
                @click="remove(row)"
              >
                删除
              </button>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="resource.columns.length + 1">{{ busy ? '正在读取…' : '暂无记录' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination">
      <span>共 {{ total }} 条，第 {{ page }} 页</span
      ><button class="secondary" :disabled="busy || page <= 1" @click="previousPage">上一页</button
      ><button class="secondary" :disabled="busy || page * 10 >= total" @click="nextPage">
        下一页
      </button>
    </div>
    <section
      v-if="selected"
      class="record-detail"
      :class="{ 'print-report': resource.key === 'reports' }"
    >
      <div class="section-heading">
        <h3>{{ resource.singular }}详情</h3>
        <button v-if="resource.key === 'reports'" class="secondary" @click="printReport">
          打印 / 导出PDF
        </button>
      </div>
      <template v-if="resource.key === 'reports'"
        ><h2>{{ selected.title }}</h2>
        <p>报告编号：{{ selected.id }}</p>
        <p>生成时间：{{ displayValue(selected.created_at) }}</p>
        <p>来源：{{ displayValue(selected.source) }}</p>
        <pre>{{ selected.content }}</pre>
      </template>
      <LessonDetail
        v-else-if="resource.key === 'tasks'"
        :api="api"
        :task-id="String(selected.id)"
        @changed="load"
      />
      <template v-else>
        <DetectionResult
          v-if="resource.key === 'detections' && selected.status === 'SUCCEEDED'"
          :api="api"
          :job-id="String(selected.id)"
        />
        <video
          v-if="preview && selected.content_type === 'video/mp4'"
          :src="preview"
          controls
          class="asset-preview"
        />
        <img v-else-if="preview" :src="preview" class="asset-preview" alt="已授权素材预览" />
        <dl>
          <template v-for="(value, key) in selected" :key="key"
            ><dt>{{ labelFor(String(key)) }}</dt>
            <dd>{{ displayValue(value) }}</dd></template
          >
        </dl>
      </template>
    </section>
  </section>
</template>
