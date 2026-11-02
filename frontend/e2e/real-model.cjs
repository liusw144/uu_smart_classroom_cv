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
const output = path.join(root, 'runtime/real-demo-evidence')
fs.mkdirSync(output, { recursive: true })
const evidence = {
  started_at: new Date().toISOString(),
  source: '公开仓库素材与本地真实模型，摄像头为公开视频虚拟设备',
  checks: [],
}
let token, browser
async function request(method, route, body) {
  const response = await fetch('http://127.0.0.1:8089/api/v1' + route, {
    method,
    headers: {
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const value = await response.json()
  assert.equal(response.status, 200, JSON.stringify(value))
  return value.data
}
async function main() {
  token = (
    await request('POST', '/auth/login', {
      school_code: 'local-school',
      username: values.TEACHER_USERNAME,
      password: values.TEACHER_PASSWORD,
    })
  ).access_token
  let task = (await request('GET', '/tasks?q=开源模型验证&size=100')).items[0]
  if (!task) {
    const schoolClass = await request('POST', '/classes', {
      name: '公开样例演示班级',
      grade: '演示',
      student_count: 0,
    })
    const course = await request('POST', '/courses', {
      name: '开源模型演示课程',
      class_id: schoolClass.id,
      term: '本机验证',
    })
    task = await request('POST', '/tasks', { name: '开源模型验证', course_id: course.id })
  }
  evidence.task_id = task.id
  browser = await chromium.launch({
    headless: true,
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    args: [
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      '--use-file-for-fake-video-capture=' + path.join(root, 'runtime/demo/virtual-camera.y4m'),
    ],
  })
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1100 },
    permissions: ['camera'],
  })
  const page = await context.newPage()
  page.setDefaultTimeout(180000)
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('http://127.0.0.1:5173')
  await page.getByLabel('学校代码').fill('local-school')
  await page.getByLabel('账号', { exact: true }).fill(values.TEACHER_USERNAME)
  await page.getByLabel('密码', { exact: true }).fill(values.TEACHER_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '视觉检测', exact: true }).click()
  await page.getByLabel('课堂任务', { exact: true }).selectOption(String(task.id))
  const selectedModel = await page.getByLabel('模型档案', { exact: true }).inputValue()
  assert.ok(selectedModel)
  for (const [filename, kind] of [
    ['image.jpg', 'IMAGE'],
    ['upstream-demo.mp4', 'VIDEO'],
  ]) {
    await page.locator('input[type=file]').setInputFiles(path.join(root, 'runtime/demo', filename))
    const submitted = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/detections') && response.request().method() === 'POST',
    )
    await page.getByRole('button', { name: '提交视觉作业', exact: true }).click()
    const job = (await (await submitted).json()).data
    await page.getByText(`作业 ${job.id}：已完成真实推理`, { exact: true }).waitFor()
    await page.getByTestId('detection-result').getByText(/采样/).waitFor()
    const result = await request('GET', `/detections/${job.id}/result`)
    assert.equal(result.media_kind, kind)
    assert.ok(result.person_instances > 0)
    assert.ok(
      result.frames.every((frame) =>
        frame.persons.every((person) => person.keypoints.length === 17),
      ),
    )
    if (kind === 'VIDEO') {
      const video = page.getByTestId('detection-result').locator('video')
      await video.evaluate(async (element) => {
        await element.play()
        await new Promise((resolve) => setTimeout(resolve, 300))
        element.pause()
      })
      assert.ok(await video.evaluate((element) => element.videoWidth > 0))
    } else {
      await page
        .getByTestId('detection-result')
        .locator('img')
        .evaluate((image) => image.decode())
    }
    fs.writeFileSync(
      path.join(output, `${kind.toLowerCase()}-result.json`),
      JSON.stringify(result, null, 2),
    )
    const artifact = await fetch(`http://127.0.0.1:8089/api/v1/detections/${job.id}/artifact`, {
      headers: { Authorization: 'Bearer ' + token },
    })
    assert.equal(artifact.status, 200)
    fs.writeFileSync(
      path.join(output, kind === 'VIDEO' ? 'annotated.mp4' : 'annotated.png'),
      Buffer.from(await artifact.arrayBuffer()),
    )
    evidence.checks.push({
      name: kind,
      job_id: job.id,
      sampled_frames: result.sampled_frames,
      person_instances: result.person_instances,
      model_hashes: result.model_hashes,
      elapsed_ms: result.elapsed_ms,
      passed: true,
    })
    if (kind === 'VIDEO') {
      const analysis = page.waitForResponse((response) =>
        response.url().endsWith(`/detections/${job.id}/analysis`),
      )
      await page.getByRole('button', { name: '生成本地AI分析', exact: true }).click()
      const response = await analysis
      const body = await response.json()
      assert.equal(response.status(), 200, JSON.stringify(body))
      assert.equal(body.data.source, 'LOCAL_QWEN3')
      await page.getByTestId('ai-report').waitFor()
      fs.writeFileSync(path.join(output, 'ai-report.txt'), body.data.content)
      evidence.checks.push({ name: 'LOCAL_QWEN3', report_id: body.data.id, passed: true })
    }
    await page.screenshot({
      path: path.join(output, `${kind.toLowerCase()}-page.png`),
      fullPage: true,
    })
  }
  const cameraJobs = []
  page.on('response', async (response) => {
    if (
      response.url().endsWith('/api/v1/detections') &&
      response.request().method() === 'POST' &&
      response.ok()
    )
      cameraJobs.push((await response.json()).data.id)
  })
  await page.getByRole('button', { name: '启动摄像头采样', exact: true }).click()
  await page.getByTestId('camera-preview').waitFor()
  await page.screenshot({ path: path.join(output, 'virtual-camera-preview.png'), fullPage: true })
  await page.getByText('本轮摄像头已采样 5 帧', { exact: true }).waitFor()
  await page
    .getByRole('button', { name: '启动摄像头采样', exact: true })
    .waitFor({ state: 'visible' })
  await page.waitForFunction(() => !document.querySelector('[data-testid="camera-preview"]'))
  assert.equal(cameraJobs.length, 5)
  for (const id of cameraJobs) {
    const result = await request('GET', `/detections/${id}/result`)
    assert.equal(result.media_kind, 'CAMERA')
    assert.ok(result.person_instances > 0)
  }
  evidence.checks.push({ name: 'PUBLIC_VIDEO_VIRTUAL_CAMERA', jobs: cameraJobs, passed: true })
  await page.screenshot({ path: path.join(output, 'camera-result-page.png'), fullPage: true })
  assert.deepEqual(errors, [])
  evidence.browser_errors = errors
  evidence.passed = true
  await request('POST', '/auth/logout')
}
main()
  .catch((error) => {
    evidence.passed = false
    let message = error.message
    for (const secret of Object.values(values).filter((value) => value.length >= 12))
      message = message.split(secret).join('[已隐藏]')
    evidence.error = message
    process.exitCode = 1
  })
  .finally(async () => {
    if (browser) await browser.close()
    evidence.finished_at = new Date().toISOString()
    fs.writeFileSync(path.join(output, 'browser-evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(
      JSON.stringify(
        { passed: evidence.passed, checks: evidence.checks, error: evidence.error },
        null,
        2,
      ),
    )
  })
