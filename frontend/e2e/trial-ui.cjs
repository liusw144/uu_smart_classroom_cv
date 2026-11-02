const { chromium } = require('playwright')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const root = path.resolve(__dirname, '../..')
const values = Object.fromEntries(
  fs
    .readFileSync(path.join(root, '.env.local'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.includes('=') && !line.startsWith('#'))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
)
const output = path.join(root, 'runtime/trial-evidence')
const evidence = JSON.parse(fs.readFileSync(path.join(output, 'trial-live.json'), 'utf8'))
let browser
async function main() {
  browser = await chromium.launch({
    headless: true,
    executablePath:
      process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
      'C:/Program Files/Google/Chrome/Application/chrome.exe',
  })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } })
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('http://127.0.0.1:5172')
  await page.getByLabel('学校代码').fill('local-school')
  await page.getByLabel('账号', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill(values.BOOTSTRAP_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const nodesResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/edge-nodes',
  )
  await page.getByRole('button', { name: '边缘节点', exact: true }).click()
  const nodes = await (await nodesResponse).json()
  assert.ok(nodes.data.items.some((item) => item.id === values.EDGE_NODE_ID && item.online))
  assert.ok(!JSON.stringify(nodes).includes('token_hash'))
  await page.locator(`[data-record-id="${values.EDGE_NODE_ID}"]`).waitFor()
  await page.screenshot({ path: path.join(output, 'node-status.png'), fullPage: true })
  await page.getByRole('button', { name: '检测记录', exact: true }).click()
  await page.getByLabel('搜索', { exact: true }).fill('开源模型验证')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const row = page.locator(`[data-record-id="${evidence.job_id}"]`)
  for (let index = 0; index < 10 && !(await row.count()); index++) {
    const next = page.getByRole('button', { name: '下一页', exact: true })
    if (!(await next.isEnabled())) break
    const pending = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/detections',
    )
    await next.click()
    await pending
  }
  await row.getByRole('button', { name: '查看', exact: true }).click()
  await page.getByText(/采样范围：0—11800/).waitFor()
  const video = page.getByTestId('detection-result').locator('video')
  await video.evaluate(async (element) => {
    await element.play()
    await new Promise((resolve) => setTimeout(resolve, 200))
    element.pause()
  })
  assert.ok(await video.evaluate((element) => element.videoWidth > 0))
  await page.screenshot({ path: path.join(output, 'full-span-page.png'), fullPage: true })
  assert.deepEqual(errors, [])
  fs.writeFileSync(
    path.join(output, 'trial-ui.json'),
    JSON.stringify(
      {
        passed: true,
        node_list: true,
        full_span_display: true,
        real_artifact_playback: true,
        page_errors: errors,
      },
      null,
      2,
    ),
  )
  console.log('节点状态和全片采样页面真实浏览器验收通过')
}
main()
  .catch((error) => {
    let message = error.message
    for (const value of Object.values(values).filter((item) => item.length >= 12))
      message = message.split(value).join('[已隐藏]')
    console.error(message)
    process.exitCode = 1
  })
  .finally(async () => {
    if (browser) await browser.close()
  })
