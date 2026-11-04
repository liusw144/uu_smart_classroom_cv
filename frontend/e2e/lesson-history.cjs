const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const output = path.resolve(__dirname, '../../.harness')
const lesson = {
  id: 1,
  tenant_id: 1,
  owner_id: 1,
  name: '课堂历史验证',
  course_id: 1,
  class_id: 1,
  scheduled_at: null,
  scheduled_end_at: null,
  room_name: '教学楼101',
  status: 'PLANNED',
  actual_started_at: null,
  actual_ended_at: null,
  archived_at: null,
  version: 0,
  metadata_locked: false,
  snapshot: {
    course_name: '数学旧课名',
    class_name: '原班级',
    grade: '高一',
    student_count: 40,
    term: '秋季',
    owner_name: '原负责人',
    captured_at: '2026-01-01T00:00:00Z',
    source: 'CREATED',
  },
}
const events = [
  {
    id: 1,
    actor_name: '教师',
    action: 'CREATED',
    version: 0,
    occurred_at: '2026-01-01T00:00:00Z',
    note: '创建记录',
  },
]
const transitions = [],
  errors = [],
  unexpected = []
const editVersions = []
let oldServer = false

async function main() {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } })
    page.on('pageerror', (error) => errors.push(error.message))
    // 浏览器只验证界面接线；全部业务请求使用合成契约响应，禁止访问本机业务数据。
    await page.route('**/api/v1/**', async (route) => {
      const req = route.request(),
        url = new URL(req.url()),
        pathname = url.pathname.replace('/api/v1', '')
      let data
      if (pathname === '/auth/login')
        data = {
          access_token: 'fixture-browser-token',
          expires_in: 3600,
          user: {
            id: 1,
            tenant_id: 1,
            username: 'fixture',
            role: 'TEACHER',
            permissions: ['session:read'],
          },
        }
      else if (pathname === '/dashboard')
        data = { class_count: 1, course_count: 1, task_count: 1, detection_count: 0 }
      else if (pathname === '/courses')
        data = { items: [{ id: 1, name: '数学旧课名' }], total: 1, page: 1, size: 100 }
      else if (pathname === '/reports') data = { items: [], total: 0, page: 1, size: 10 }
      else if (pathname === '/models')
        data = {
          items: [{ id: 1, name: '测试模型', code: 'fixture', enabled: true }],
          total: 1,
          page: 1,
          size: 100,
        }
      else if (pathname === '/capabilities')
        data = { execution_mode: 'EDGE', model_version: 'fixture', node_available: true }
      else if (pathname === '/tasks') data = { items: [{ ...lesson }], total: 1, page: 1, size: 10 }
      else if (pathname === '/tasks/1' && req.method() === 'PUT') {
        editVersions.push(req.postDataJSON().version)
        return route.fulfill({
          status: 409,
          json: { error: { message: '课堂已被修改，请刷新后重试' } },
        })
      } else if (pathname === '/tasks/1')
        data = oldServer ? { id: 1, name: lesson.name } : { ...lesson }
      else if (pathname === '/tasks/1/events')
        data = { items: [...events].reverse(), total: events.length, page: 1, size: 10 }
      else if (pathname === '/tasks/1/transition' && req.method() === 'POST') {
        const body = req.postDataJSON()
        assert.equal(body.version, lesson.version)
        transitions.push(body.action)
        lesson.status = { START: 'IN_PROGRESS', COMPLETE: 'COMPLETED', ARCHIVE: 'ARCHIVED' }[
          body.action
        ]
        lesson.version++
        lesson.metadata_locked = true
        if (body.action === 'START') lesson.actual_started_at = '2026-01-01T09:00:00Z'
        if (body.action === 'COMPLETE') lesson.actual_ended_at = '2026-01-01T10:00:00Z'
        if (body.action === 'ARCHIVE') lesson.archived_at = '2026-01-01T11:00:00Z'
        events.push({
          id: events.length + 1,
          actor_name: '教师',
          action: body.action,
          version: lesson.version,
          occurred_at: '2026-01-01T11:00:00Z',
          note: '状态记录',
        })
        data = { ...lesson }
      } else {
        unexpected.push(`${req.method()} ${pathname}`)
        return route.fulfill({ status: 500, json: { error: { message: '未定义的测试请求' } } })
      }
      await route.fulfill({ status: 200, json: { data } })
    })
    await page.goto(process.env.LESSON_E2E_BASE_URL || 'http://127.0.0.1:15173')
    await page.getByLabel('学校代码', { exact: true }).fill('fixture')
    await page.getByLabel('账号', { exact: true }).fill('fixture')
    await page.getByLabel('密码', { exact: true }).fill('fixture-only-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByRole('button', { name: '课堂任务', exact: true }).click()
    await page.getByRole('button', { name: '编辑', exact: true }).click()
    await page.getByLabel('任务名称', { exact: true }).fill('旧表单内容')
    lesson.version = 1
    events.push({
      id: 2,
      actor_name: '同账号其他窗口',
      action: 'UPDATED',
      version: 1,
      occurred_at: '2026-01-01T08:00:00Z',
      note: '另一窗口修改计划',
    })
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const edited = page.waitForResponse((response) => response.request().method() === 'PUT')
    await page.getByRole('button', { name: '保存', exact: true }).click()
    await edited
    assert.deepEqual(editVersions, [0])
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await page.getByRole('button', { name: '查看', exact: true }).click()
    const detail = page.getByTestId('lesson-history')
    await detail.getByText('原负责人', { exact: true }).waitFor()
    await detail.getByRole('button', { name: '开始课堂', exact: true }).click()
    await detail.getByRole('button', { name: '结束课堂', exact: true }).waitFor()
    await detail.getByRole('button', { name: '结束课堂', exact: true }).click()
    await detail.getByRole('button', { name: '归档课堂', exact: true }).waitFor()
    page.once('dialog', (dialog) => dialog.accept())
    await detail.getByRole('button', { name: '归档课堂', exact: true }).click()
    await detail.getByRole('heading', { name: '课堂历史验证 · 已归档', exact: true }).waitFor()
    assert.equal(await detail.getByRole('button', { name: '开始课堂', exact: true }).count(), 0)
    assert.equal(await page.getByRole('button', { name: '编辑', exact: true }).count(), 0)
    assert.deepEqual(transitions, ['START', 'COMPLETE', 'ARCHIVE'])
    assert.deepEqual(errors, [])
    assert.deepEqual(unexpected, [])
    fs.mkdirSync(output, { recursive: true })
    await page.screenshot({ path: path.join(output, 'lesson-history-ui.png'), fullPage: true })
    await page.getByRole('button', { name: '视觉检测', exact: true }).click()
    await page
      .getByText('当前通过边缘节点执行锁定的YOLOX与RTMPose模型。', { exact: true })
      .waitFor()
    assert.equal(await page.getByLabel('课堂任务', { exact: true }).locator('option').count(), 1)
    oldServer = true
    await page.getByRole('button', { name: '课堂任务', exact: true }).click()
    await page.getByRole('button', { name: '查看', exact: true }).click()
    await page
      .getByTestId('lesson-history')
      .getByRole('alert')
      .filter({ hasText: '请先升级后端' })
      .waitFor({ timeout: 3000 })
    assert.deepEqual(errors, [])
    await page.getByRole('button', { name: '教务汇总报告', exact: true }).click()
    await page.getByRole('button', { name: '新增教务汇总报告', exact: true }).click()
    assert.equal(await page.getByLabel('课堂任务', { exact: true }).locator('option').count(), 1)
    const proof = {
      passed: true,
      scope: '合成API响应的真实Chrome界面测试；后端另经MySQL集成测试',
      transitions,
      stale_edit_versions: editVersions,
      archived_choices_filtered: true,
      old_server_upgrade_message: true,
      errors,
      unexpected,
    }
    fs.writeFileSync(path.join(output, 'lesson-history-ui.json'), JSON.stringify(proof, null, 2))
    console.log(JSON.stringify(proof))
  } finally {
    await browser.close()
  }
}
main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
