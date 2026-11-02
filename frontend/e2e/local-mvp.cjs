const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const values = Object.fromEntries(
  fs
    .readFileSync(path.join(root, '.env.local'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.includes('=') && !line.startsWith('#'))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
)
const output = path.join(root, '.harness/local-e2e')
fs.mkdirSync(output, { recursive: true })
const secretValues = [values.BOOTSTRAP_PASSWORD, values.TEACHER_PASSWORD].filter(Boolean)
const results = []
function redact(message) {
  for (const secret of secretValues) message = message.split(secret).join('[凭据已隐藏]')
  return message
}
async function run(name, action) {
  try {
    await action()
    results.push({ name, status: 'passed' })
  } catch (error) {
    results.push({ name, status: 'failed', message: redact(error.message) })
    throw error
  }
}
async function request(method, route, token, body) {
  const response = await fetch(`http://127.0.0.1:8089/api/v1${route}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const value = await response.json()
  assert.equal(response.status, 200, JSON.stringify(value))
  return value.data
}
async function save(page, route, method = 'POST') {
  const pending = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/v1${route}` &&
      response.request().method() === method,
  )
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const response = await pending
  const body = await response.json()
  assert.equal(response.status(), 200, JSON.stringify(body))
  return body.data
}
async function navigate(page, name) {
  await page.getByRole('button', { name, exact: true }).click()
  await page.getByRole('heading', { name, exact: true }).waitFor()
}

async function login(page, audience, username, password) {
  await page.goto(`http://127.0.0.1:${audience === 'admin' ? 5172 : 5173}/`)
  await page.getByLabel('学校代码', { exact: true }).fill('local-school')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  const pending = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/auth/login',
  )
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const response = await pending
  assert.equal(response.status(), 200)
  await page.getByRole('button', { name: '退出登录', exact: true }).waitFor()
  return (await response.json()).data.access_token
}

;(async () => {
  const chrome =
    process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
    'C:/Program Files/Google/Chrome/Application/chrome.exe'
  const browser = await chromium.launch({
    headless: true,
    ...(fs.existsSync(chrome) ? { executablePath: chrome } : {}),
  })
  const page = await browser.newPage()
  const teacherPage = await browser.newPage()
  const suffix = Date.now().toString(36)
  const username = `e2e-${suffix}`
  const password = require('node:crypto').randomBytes(18).toString('base64url')
  const changedPassword = require('node:crypto').randomBytes(18).toString('base64url')
  secretValues.push(password, changedPassword)
  let adminToken,
    teacherToken,
    userId,
    classId,
    courseId,
    taskId,
    modelId,
    noticeId,
    reportId,
    jobId,
    assetId,
    avatarId
  let currentPassword = password
  let realMode = false
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  teacherPage.on('pageerror', (error) => pageErrors.push(error.message))
  try {
    await run('管理端真实登录及导航', async () => {
      adminToken = await login(page, 'admin', 'admin', values.BOOTSTRAP_PASSWORD)
      await navigate(page, '班级管理')
    })
    await run('管理端新增教师', async () => {
      await navigate(page, '用户管理')
      await page.getByRole('button', { name: '新增用户', exact: true }).click()
      await page.getByLabel('新账号', { exact: true }).fill(username)
      await page.getByLabel('初始密码', { exact: true }).fill(password)
      await page.getByLabel('姓名', { exact: true }).fill('端到端验证教师')
      userId = (await save(page, '/users')).id
    })
    await run('教师端真实登录', async () => {
      teacherToken = await login(teacherPage, 'teacher', username, password)
      assert.equal(
        await teacherPage.getByRole('button', { name: '用户管理', exact: true }).count(),
        0,
      )
    })
    await run('教师新增与编辑班级', async () => {
      await navigate(teacherPage, '班级管理')
      await teacherPage.getByRole('button', { name: '新增班级', exact: true }).click()
      await teacherPage.getByLabel('班级名称', { exact: true }).fill(`验证班级-${suffix}`)
      await teacherPage.getByLabel('年级', { exact: true }).fill('高一')
      await teacherPage.getByLabel('班级人数', { exact: true }).fill('40')
      classId = (await save(teacherPage, '/classes')).id
      await teacherPage
        .getByRole('row')
        .filter({ hasText: `验证班级-${suffix}` })
        .getByRole('button', { name: '编辑', exact: true })
        .click()
      await teacherPage.getByLabel('班级人数', { exact: true }).fill('42')
      await save(teacherPage, `/classes/${classId}`, 'PUT')
    })
    await run('教师课程继承班级关系', async () => {
      await navigate(teacherPage, '课程管理')
      await teacherPage.getByRole('button', { name: '新增课程', exact: true }).click()
      await teacherPage.getByLabel('课程名称', { exact: true }).fill(`验证课程-${suffix}`)
      await teacherPage.getByLabel('所属班级', { exact: true }).selectOption(String(classId))
      await teacherPage.getByLabel('学期', { exact: true }).fill('2026秋季')
      courseId = (await save(teacherPage, '/courses')).id
    })
    await run('教师创建课堂任务', async () => {
      await navigate(teacherPage, '课堂任务')
      await teacherPage.getByRole('button', { name: '新增课堂任务', exact: true }).click()
      await teacherPage.getByLabel('任务名称', { exact: true }).fill(`验证课堂-${suffix}`)
      await teacherPage.getByLabel('所属课程', { exact: true }).selectOption(String(courseId))
      await teacherPage.getByLabel('计划时间（选填）', { exact: true }).fill('2026-11-03T08:30')
      taskId = (await save(teacherPage, '/tasks')).id
      await teacherPage
        .getByRole('row')
        .filter({ hasText: `验证课堂-${suffix}` })
        .getByRole('button', { name: '编辑', exact: true })
        .click()
      assert.equal(
        await teacherPage.getByLabel('计划时间（选填）', { exact: true }).inputValue(),
        '2026-11-03T08:30',
      )
      await teacherPage.getByRole('button', { name: '取消', exact: true }).click()
      await teacherPage.screenshot({ path: path.join(output, 'teacher-tasks.png'), fullPage: true })
    })
    await run('有引用的班级删除被拒绝', async () => {
      await navigate(teacherPage, '班级管理')
      teacherPage.once('dialog', (dialog) => dialog.accept())
      await teacherPage
        .getByRole('row')
        .filter({ hasText: `验证班级-${suffix}` })
        .getByRole('button', { name: '删除', exact: true })
        .click()
      await teacherPage.getByRole('alert').filter({ hasText: '引用' }).waitFor()
    })
    await run('管理端维护模型档案', async () => {
      await navigate(page, '模型档案')
      await page.getByRole('button', { name: '新增模型档案', exact: true }).click()
      await page.getByLabel('模型名称', { exact: true }).fill(`验证模型-${suffix}`)
      await page.getByLabel('版本代码', { exact: true }).fill(`e2e-${suffix}`)
      await page.getByLabel('说明', { exact: true }).fill('人工测试档案，不代表模型加载')
      modelId = (await save(page, '/models')).id
    })
    const png = Buffer.from(
      await teacherPage.evaluate(() => {
        const canvas = document.createElement('canvas')
        canvas.width = 2
        canvas.height = 2
        return canvas.toDataURL('image/png').split(',')[1]
      }),
      'base64',
    )
    await run('图片提交与当前视觉模式反馈', async () => {
      realMode = (await request('GET', '/capabilities', teacherToken)).execution_mode === 'EDGE'
      const selectedModel = realMode
        ? (await request('GET', '/models?q=yolox-tiny-rtmpose-s-v1', teacherToken)).items[0].id
        : modelId
      await navigate(teacherPage, '视觉检测')
      await teacherPage.getByLabel('课堂任务', { exact: true }).selectOption(String(taskId))
      await teacherPage.getByLabel('模型档案', { exact: true }).selectOption(String(selectedModel))
      await teacherPage
        .getByLabel('图片或视频', { exact: true })
        .setInputFiles({ name: 'synthetic.png', mimeType: 'image/png', buffer: png })
      const pending = teacherPage.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/detections' &&
          response.request().method() === 'POST',
      )
      await teacherPage.getByRole('button', { name: '提交视觉作业', exact: true }).click()
      const response = await pending
      assert.equal(response.status(), 200)
      const job = (await response.json()).data
      jobId = job.id
      assetId = job.asset_id
      if (realMode) {
        assert.equal(job.status, 'QUEUED')
        await teacherPage
          .getByText(`作业 ${jobId}：已完成真实推理`, { exact: true })
          .waitFor({ timeout: 60000 })
      } else {
        assert.equal(job.status, 'FAILED')
        assert.equal(job.error_code, 'MODEL_UNAVAILABLE')
      }
    })
    await run('素材列表与授权图片预览', async () => {
      await navigate(teacherPage, '素材管理')
      await teacherPage
        .getByRole('row')
        .filter({ hasText: 'synthetic.png' })
        .getByRole('button', { name: '查看', exact: true })
        .click()
      const image = teacherPage.getByRole('img', { name: '已授权素材预览', exact: true })
      await image.waitFor()
      assert.equal(await image.evaluate((element) => element.naturalWidth), 2)
    })
    await run('检测状态记录与当前执行模式一致', async () => {
      await navigate(teacherPage, '检测记录')
      await teacherPage
        .getByLabel('检测状态', { exact: true })
        .selectOption(realMode ? 'SUCCEEDED' : 'FAILED')
      await teacherPage.getByRole('button', { name: '查询', exact: true }).click()
      await teacherPage
        .getByRole('row')
        .filter({ hasText: `验证课堂-${suffix}` })
        .filter({ hasText: realMode ? '成功' : '模型未加载' })
        .waitFor()
    })
    await run('教务模板报告与中文PDF输出', async () => {
      await navigate(teacherPage, '教务汇总报告')
      await teacherPage.getByRole('button', { name: '新增教务汇总报告', exact: true }).click()
      await teacherPage.getByLabel('课堂任务', { exact: true }).selectOption(String(taskId))
      await teacherPage.getByLabel('报告标题', { exact: true }).fill(`教务验证报告-${suffix}`)
      reportId = (await save(teacherPage, '/reports')).id
      await teacherPage.locator('.print-report').waitFor()
      assert.ok(
        (await teacherPage.locator('.print-report').innerText()).includes('不包含课堂行为判断'),
      )
      await teacherPage.pdf({
        path: path.join(output, 'teaching-report.pdf'),
        format: 'A4',
        printBackground: true,
      })
      await teacherPage.screenshot({
        path: path.join(output, 'teacher-report.png'),
        fullPage: true,
      })
    })
    await run('教师头像与个人资料保存', async () => {
      await navigate(teacherPage, '个人中心')
      await teacherPage.getByLabel('显示姓名', { exact: true }).fill('本机验证教师')
      const pending = teacherPage.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/assets' &&
          response.request().method() === 'POST',
      )
      await teacherPage
        .getByLabel('头像图片', { exact: true })
        .setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: png })
      avatarId = (await (await pending).json()).data.id
      await teacherPage.getByRole('button', { name: '保存资料', exact: true }).click()
      await teacherPage.getByRole('status').filter({ hasText: '资料已保存' }).waitFor()
      assert.ok(await teacherPage.getByRole('img', { name: '当前头像' }).isVisible())
    })
    await run('修改密码后必须重新登录', async () => {
      await teacherPage.getByLabel('原密码', { exact: true }).fill(password)
      await teacherPage.getByLabel('新密码', { exact: true }).fill(changedPassword)
      await teacherPage.getByRole('button', { name: '修改密码并重新登录', exact: true }).click()
      await teacherPage.getByRole('button', { name: '登录', exact: true }).waitFor()
      currentPassword = changedPassword
      teacherToken = await login(teacherPage, 'teacher', username, changedPassword)
    })
    await run('公告发布后教师可见', async () => {
      await navigate(page, '教学公告')
      await page.getByRole('button', { name: '新增公告', exact: true }).click()
      await page.getByLabel('公告标题', { exact: true }).fill(`验证公告-${suffix}`)
      await page.getByLabel('公告正文', { exact: true }).fill('仅为本机端到端人工测试公告')
      noticeId = (await save(page, '/notices')).id
      await navigate(teacherPage, '教学公告')
      await teacherPage
        .getByRole('row')
        .filter({ hasText: `验证公告-${suffix}` })
        .waitFor()
    })
    await run('管理员审计查询', async () => {
      await navigate(page, '操作日志')
      await page.getByLabel('搜索', { exact: true }).fill(username)
      await page.getByRole('button', { name: '查询', exact: true }).click()
      await page.getByRole('row').filter({ hasText: username }).first().waitFor()
      assert.equal(pageErrors.length, 0, pageErrors.join('; '))
      await page.screenshot({ path: path.join(output, 'admin-audit.png'), fullPage: true })
    })
  } finally {
    if (adminToken && userId) {
      try {
        if (teacherToken)
          await request('PUT', '/profile', teacherToken, {
            display_name: '端到端验证教师',
            email: '',
            avatar_asset_id: null,
          })
        for (const [route, id] of [
          ['reports', reportId],
          ['detections', jobId],
          ['assets', assetId],
          ['assets', avatarId],
          ['tasks', taskId],
          ['courses', courseId],
          ['classes', classId],
          ['notices', noticeId],
          ['models', modelId],
          ['users', userId],
        ])
          if (id) await request('DELETE', `/${route}/${id}`, adminToken)
        results.push({ name: '按本次记录编号清理人工测试业务数据', status: 'passed' })
      } catch (error) {
        results.push({
          name: '清理人工测试业务数据',
          status: 'failed',
          message: redact(error.message),
        })
        process.exitCode = 1
      }
    }
    fs.writeFileSync(
      path.join(output, 'results.json'),
      JSON.stringify(
        {
          scope: `真实Vue→Java→MySQL/Redis，合成2像素图片；视觉模式${realMode ? '真实模型' : '明确不可用'}`,
          results,
        },
        null,
        2,
      ),
    )
    await browser.close()
  }
  console.log(
    JSON.stringify(
      {
        total: results.length,
        passed: results.filter((item) => item.status === 'passed').length,
        failed: results.filter((item) => item.status === 'failed'),
      },
      null,
      2,
    ),
  )
})().catch((error) => {
  console.error(redact(error.message))
  process.exitCode = 1
})
