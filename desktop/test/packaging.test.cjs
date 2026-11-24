const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('desktop distributions are portable only', () => {
  const pkg = require('../package.json');
  assert.deepEqual(pkg.build.win.target, ['portable']);
  assert.deepEqual(pkg.build.linux.target, ['AppImage']);
  assert.deepEqual(pkg.build.mac.target, ['zip']);
  assert.equal(pkg.build.nsis, undefined);
  const workflowPath = path.join(__dirname, '../../.github/workflows/desktop-build.yml');
  if (!fs.existsSync(workflowPath)) return;
  const workflow = fs.readFileSync(workflowPath, 'utf8');
  assert.match(workflow, /target: --mac --dir/);
  assert.match(workflow, /python scripts\/package_portable.py/);
  assert.doesNotMatch(workflow, /target:.*(?:nsis|dmg|deb)/);
});
