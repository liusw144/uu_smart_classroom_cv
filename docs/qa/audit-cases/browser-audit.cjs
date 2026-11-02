const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

const output = __dirname;
const results = [];
const token = 'browser-audit-only-not-a-real-credential';

async function runCase(browser, id, name, port, role, behavior, verify) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  const requests = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/v1/auth/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    requests.push({ pathname, method: request.method(), authorization: request.headers().authorization });
    if (pathname.endsWith('/login')) {
      const data = request.postDataJSON();
      assert.equal(data.school_code, 'audit-school');
      if (behavior === 'wrong-password') {
        return route.fulfill({ status: 401, json: { error: { code: 'UNAUTHORIZED', message: '身份无效', request_id: 'audit' } } });
      }
      return route.fulfill({ status: 200, json: { data: { access_token: token, expires_in: 7200, user: { id: 7, tenant_id: 1, username: 'audit-user', role, permissions: ['session:read'] } } } });
    }
    if (pathname.endsWith('/logout')) {
      assert.equal(request.headers().authorization, `Bearer ${token}`);
      return route.fulfill({ status: behavior === 'logout-failure' ? 500 : 200, json: { data: true } });
    }
    return route.fulfill({ status: 404, json: {} });
  });
  try {
    await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'networkidle' });
    await page.getByLabel('学校代码').fill('audit-school');
    await page.getByLabel('账号', { exact: true }).fill('audit-user');
    await page.getByLabel('密码', { exact: true }).fill('synthetic-password');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await verify(page, requests);
    assert.deepEqual(errors, []);
    results.push({ id, name, status: 'passed', scope: '真实浏览器＋受控 API fixture，不代表 Java/数据库端到端' });
  } catch (error) {
    results.push({ id, name, status: 'failed', message: error.message, scope: '真实浏览器＋受控 API fixture' });
  } finally {
    await context.close();
  }
}

async function signedIn(page) {
  await page.getByRole('button', { name: '退出登录', exact: true }).waitFor();
  assert.match(await page.locator('.panel h2').innerText(), /audit-user/);
}

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', headless: true });
  try {
    await runCase(browser, 'UI01', '教师登录后显示学校身份', 15173, 'TEACHER', 'success', async page => {
      await signedIn(page);
      assert.equal((await page.locator('dd').first().innerText()).trim(), '1');
      await page.screenshot({ path: path.join(output, 'teacher-fixture.png'), fullPage: true });
    });
    await runCase(browser, 'UI02', '管理员登录后显示学校身份', 15172, 'SCHOOL_ADMIN', 'success', async page => {
      await signedIn(page);
      assert.equal((await page.locator('dd').nth(1).innerText()).trim(), 'SCHOOL_ADMIN');
    });
    await runCase(browser, 'UI03', '教师不能进入管理界面', 15172, 'TEACHER', 'success', async (page, requests) => {
      await page.getByRole('alert').waitFor();
      await page.getByRole('button', { name: '登录', exact: true }).waitFor();
      assert.equal(requests.filter(request => request.pathname.endsWith('/logout')).length, 1);
      assert.equal(await page.getByRole('button', { name: '退出登录', exact: true }).count(), 0);
    });
    await runCase(browser, 'UI04', '登录失败显示反馈并清空密码', 15173, 'TEACHER', 'wrong-password', async page => {
      await page.getByRole('alert').waitFor();
      assert.equal(await page.getByLabel('密码', { exact: true }).inputValue(), '');
      await page.screenshot({ path: path.join(output, 'login-error-fixture.png'), fullPage: true });
    });
    await runCase(browser, 'UI05', '退出时发送凭据并回到登录页', 15173, 'TEACHER', 'success', async (page, requests) => {
      await signedIn(page);
      await page.getByRole('button', { name: '退出登录', exact: true }).click();
      await page.getByRole('button', { name: '登录', exact: true }).waitFor();
      assert.equal(requests.filter(request => request.pathname.endsWith('/logout')).length, 1);
    });
    await runCase(browser, 'UI06', '远端退出失败时清理本地界面', 15173, 'TEACHER', 'logout-failure', async page => {
      await signedIn(page);
      await page.getByRole('button', { name: '退出登录', exact: true }).click();
      await page.getByRole('button', { name: '登录', exact: true }).waitFor();
      await page.getByRole('alert').waitFor();
    });
    await runCase(browser, 'UI07', '刷新不会保留内存令牌', 15173, 'TEACHER', 'success', async page => {
      await signedIn(page);
      assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
      await page.reload();
      await page.getByRole('button', { name: '登录', exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: '退出登录', exact: true }).count(), 0);
    });
  } finally {
    await browser.close();
  }
  await fs.writeFile(path.join(output, 'browser-results.json'), JSON.stringify(results, null, 2), 'utf8');
  console.log(JSON.stringify({ total: results.length, passed: results.filter(result => result.status === 'passed').length, failed: results.filter(result => result.status === 'failed') }, null, 2));
  if (results.some(result => result.status === 'failed')) process.exitCode = 1;
})().catch(error => { console.error(error.message); process.exitCode = 1; });
