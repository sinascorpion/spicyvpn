const { _electron: electron } = require('playwright');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'spicy-desktop-ui-'));
  const env = { ...process.env, SPICY_TEST_DIRECTORY: directory, SPICY_SMOKE: '1' }; delete env.ELECTRON_RUN_AS_NODE;
  const args = [path.resolve(__dirname, '..')];
  if (process.platform === 'linux') args.push('--password-store=gnome-libsecret');
  const application = await electron.launch({ executablePath: require('electron'), args, env, timeout: 30000 });
  const errors = [];
  try {
    const page = await application.firstWindow(); page.on('pageerror', error => errors.push(error.message));
    await page.locator('#status').waitFor();
    await page.waitForFunction(() => document.querySelectorAll('svg').length > 5);
    await page.locator('[data-tab=custom]').click(); await page.locator('#add').click();
    await page.locator('#profile-text').fill('socks5://127.0.0.1:1080#A%20long%20configuration%20name%20for%20desktop');
    await page.locator('#editor button[type=submit]').click();
    await page.locator('.profile-row').waitFor();
    assert.equal(await page.locator('.profile-row h3').textContent(), 'A long configuration name for desktop');
    await page.locator('#theme').click(); await page.waitForFunction(() => document.body.classList.contains('light'));
    await page.locator('#new-folder').click(); await page.locator('#panel input').fill('Test Folder'); await page.locator('#panel form button').click();
    await page.getByRole('button', { name: /Test Folder/ }).waitFor();
    await page.locator('#theme').click();
    await fs.mkdir(path.resolve(__dirname, '../.test-data'), { recursive: true });
    const capture = async name => {
      const encoded = await application.evaluate(async ({ BrowserWindow }) => {
        const image = await BrowserWindow.getAllWindows()[0].webContents.capturePage(undefined, { stayHidden: true });
        return image.toPNG().toString('base64');
      });
      assert(encoded.length > 5000, 'Rendered screenshot must not be blank');
      await fs.writeFile(path.resolve(__dirname, '../.test-data/' + name), Buffer.from(encoded, 'base64'));
    };
    await capture('custom-1100.png');
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(800, 700));
    await capture('custom-800.png');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'No horizontal overflow');
    await page.locator('[data-tab=auto]').click(); await capture('auto-800.png');
    assert.equal(await page.locator('.connect-logo img').evaluate(img => img.naturalWidth > 0), true, 'Actual brand asset renders');
    assert.deepEqual(errors, []);
    console.log('Electron UI passed: imports, encrypted storage, theme, folders, 1100/800 layouts and logo.');
  } finally { await application.close(); await fs.rm(directory, { recursive: true, force: true }); }
})().catch(error => { console.error(error); process.exitCode = 1; });
