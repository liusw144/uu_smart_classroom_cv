export interface Field {
  key: string
  label: string
  type?: 'text' | 'number' | 'textarea' | 'checkbox' | 'datetime-local' | 'password'
  reference?: string
  options?: { value: string; label: string }[]
  required?: boolean
  createOnly?: boolean
  editOnly?: boolean
  max?: number
}
export interface Resource {
  key: string
  title: string
  singular: string
  columns: { key: string; label: string }[]
  fields: Field[]
  editable?: boolean
  deletable?: boolean
  hint?: string
}
const column = (key: string, label: string) => ({ key, label })
const named = column('name', '名称')
const id = column('id', '编号')
const owner = column('owner_id', '负责人编号')
export const resources: Record<string, Resource> = {
  'edge-nodes': {
    key: 'edge-nodes',
    title: '边缘节点',
    singular: '边缘节点',
    columns: [
      id,
      named,
      column('enabled', '启用'),
      column('online', '在线'),
      column('last_seen_at', '最近心跳'),
      column('queued', '等待作业'),
      column('running', '执行中'),
    ],
    fields: [],
    hint: '仅显示本校节点；最近90秒有认证活动视为在线，密钥不向浏览器返回。',
  },
  assets: {
    key: 'assets',
    title: '素材管理',
    singular: '素材',
    columns: [
      column('filename', '文件名'),
      column('content_type', '格式'),
      column('size', '字节数'),
      owner,
    ],
    fields: [],
    deletable: true,
    hint: '图片上传后均可在此找回。被头像或检测记录引用的素材不能删除；移除头像并保存资料后可清理旧图。',
  },
  classes: {
    key: 'classes',
    title: '班级管理',
    singular: '班级',
    columns: [id, named, column('grade', '年级'), column('student_count', '教务人数'), owner],
    fields: [
      { key: 'name', label: '班级名称', required: true, max: 128 },
      { key: 'grade', label: '年级', required: true, max: 64 },
      { key: 'student_count', label: '班级人数', type: 'number', required: true, max: 500 },
      { key: 'owner_id', label: '负责人（选填）', reference: 'users' },
    ],
    editable: true,
    deletable: true,
  },
  courses: {
    key: 'courses',
    title: '课程管理',
    singular: '课程',
    columns: [id, named, column('class_id', '班级编号'), column('term', '学期'), owner],
    fields: [
      { key: 'name', label: '课程名称', required: true, max: 128 },
      { key: 'class_id', label: '所属班级', reference: 'classes', required: true },
      { key: 'term', label: '学期', required: true, max: 64 },
    ],
    editable: true,
    deletable: true,
  },
  tasks: {
    key: 'tasks',
    title: '课堂任务',
    singular: '课堂任务',
    columns: [
      id,
      named,
      column('course_id', '课程编号'),
      column('class_id', '班级编号'),
      column('scheduled_at', '计划时间'),
      column('status', '课堂状态'),
      column('room_name', '教室'),
    ],
    fields: [
      { key: 'name', label: '任务名称', required: true, max: 128 },
      { key: 'course_id', label: '所属课程', reference: 'courses', required: true },
      { key: 'scheduled_at', label: '计划时间（选填）', type: 'datetime-local' },
      { key: 'scheduled_end_at', label: '计划结束（选填）', type: 'datetime-local' },
      { key: 'room_name', label: '教室（选填）', max: 128 },
    ],
    editable: true,
    deletable: true,
  },
  users: {
    key: 'users',
    title: '用户管理',
    singular: '用户',
    columns: [
      id,
      column('username', '账号'),
      column('display_name', '姓名'),
      column('role', '角色'),
      column('enabled', '启用'),
    ],
    fields: [
      { key: 'username', label: '新账号', required: true, createOnly: true, max: 64 },
      {
        key: 'password',
        label: '初始密码',
        type: 'password',
        required: true,
        createOnly: true,
        max: 72,
      },
      { key: 'display_name', label: '姓名', required: true, max: 128 },
      {
        key: 'role',
        label: '用户角色',
        options: [
          { value: 'TEACHER', label: '教师' },
          { value: 'SCHOOL_ADMIN', label: '学校管理员' },
        ],
        required: true,
      },
      { key: 'enabled', label: '启用账号', type: 'checkbox', editOnly: true },
    ],
    editable: true,
    deletable: true,
    hint: '新增密码至少12字符、UTF-8编码后最多72字节。停用或修改账号后，该账号需重新登录。',
  },
  notices: {
    key: 'notices',
    title: '教学公告',
    singular: '公告',
    columns: [id, column('title', '标题'), column('published', '已发布')],
    fields: [
      { key: 'title', label: '公告标题', required: true, max: 128 },
      { key: 'content', label: '公告正文', type: 'textarea', required: true, max: 20000 },
      { key: 'published', label: '立即发布', type: 'checkbox' },
    ],
    editable: true,
    deletable: true,
  },
  models: {
    key: 'models',
    title: '模型档案',
    singular: '模型档案',
    columns: [id, named, column('code', '版本代码'), column('enabled', '启用')],
    fields: [
      { key: 'name', label: '模型名称', required: true, max: 128 },
      { key: 'code', label: '版本代码', required: true, max: 64 },
      { key: 'description', label: '说明', type: 'textarea', max: 2000 },
      { key: 'enabled', label: '启用档案', type: 'checkbox' },
    ],
    editable: true,
    deletable: true,
    hint: '档案用于管理版本信息；只有与当前锁定版本一致的档案能用于边缘推理。',
  },
  categories: {
    key: 'categories',
    title: '行为类别',
    singular: '行为类别',
    columns: [id, named, column('code', '类别代码'), column('enabled', '启用')],
    fields: [
      { key: 'name', label: '类别名称', required: true, max: 128 },
      { key: 'code', label: '类别代码', required: true, max: 64 },
      { key: 'description', label: '说明', type: 'textarea', max: 2000 },
      { key: 'enabled', label: '启用类别', type: 'checkbox' },
    ],
    editable: true,
    deletable: true,
    hint: '类别档案不会改变模型权重及姿态规则的识别能力。',
  },
  detections: {
    key: 'detections',
    title: '检测记录',
    singular: '检测记录',
    columns: [
      column('task_name', '课堂任务'),
      column('status', '状态'),
      column('error_code', '原因'),
      column('model_version', '模型版本'),
      column('created_at', '提交时间'),
    ],
    fields: [],
    deletable: true,
    hint: '成功作业可查看实际人物框、姿态、视频和本地AI分析；未接入模型时保存明确的失败原因。',
  },
  reports: {
    key: 'reports',
    title: '教务汇总报告',
    singular: '教务汇总报告',
    columns: [column('title', '标题'), column('source', '来源'), column('created_at', '生成时间')],
    fields: [
      { key: 'task_id', label: '课堂任务', reference: 'tasks', required: true },
      { key: 'title', label: '报告标题', required: true, max: 128 },
    ],
    deletable: true,
    hint: '依据真实教务数据生成本地模板汇总，不包含课堂行为判断；在详情中可打印为PDF。',
  },
  logs: {
    key: 'logs',
    title: '操作日志',
    singular: '日志',
    columns: [
      column('created_at', '时间'),
      column('username', '账号'),
      column('method', '操作方式'),
      column('path', '请求路径'),
      column('status', '状态码'),
    ],
    fields: [],
    hint: '当前为已登录请求审计，不记录密码、令牌或请求正文。',
  },
}
export function displayValue(value: unknown): string {
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T.*Z$/.test(value)) {
    const date = new Date(value)
    if (!Number.isNaN(date.getTime())) return date.toLocaleString('zh-CN', { hour12: false })
  }
  if (value === null || value === undefined || value === '') return '—'
  if (value === true) return '是'
  if (value === false) return '否'
  const labels: Record<string, string> = {
    TEACHER: '教师',
    SCHOOL_ADMIN: '学校管理员',
    FAILED: '失败',
    RUNNING: '处理中',
    QUEUED: '等待节点',
    CANCELLED: '已取消',
    PLANNED: '待开课',
    LEGACY: '历史待确认',
    IN_PROGRESS: '课堂进行中',
    COMPLETED: '课堂已结束',
    ARCHIVED: '已归档',
    SUCCEEDED: '成功',
    MODEL_UNAVAILABLE: '模型未加载',
    VISION_UNAVAILABLE: '视觉服务不可用',
    VISION_RESULT_UNSUPPORTED: '结果协议未接入',
    LOCAL_TEACHING_TEMPLATE: '本地教务模板',
    LOCAL_QWEN3: '本地Qwen3生成',
  }
  return labels[String(value)] || String(value)
}
