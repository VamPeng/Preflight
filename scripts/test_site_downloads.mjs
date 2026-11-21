import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = readFileSync(new URL('../site/introduction/app.js', import.meta.url), 'utf8');
class Element {
  constructor(attrs = {}) { this.attrs = attrs; this.innerHTML = ''; this.classList = { toggle() {} }; }
  getAttribute(k) { return this.attrs[k] ?? null; }
  setAttribute(k, v) { this.attrs[k] = v; }
  removeAttribute(k) { delete this.attrs[k]; }
  hasAttribute(k) { return k in this.attrs; }
}
async function render(platform, architecture, lang = 'en') {
  const root = new Element({ 'data-dl-mac': '/arm64.dmg', 'data-dl-mac-x64': '/x64.dmg',
    'data-dl-windows': '/x64.exe', 'data-version': '1.0.1', 'data-version-windows': '1.0.0' });
  const button = new Element({ 'data-dl': '' }), list = new Element(), version = new Element(), top = new Element();
  const navigator = { platform, userAgent: platform === 'MacIntel' ? 'Macintosh; Intel Mac OS X' : platform };
  if (architecture) navigator.userAgentData = { platform: 'macOS', getHighEntropyValues: async () => ({ architecture }) };
  vm.runInNewContext(source, { navigator, localStorage: { getItem() { return null; } }, location: { search: '?lang=' + lang },
    window: { scrollY: 0, addEventListener() {} }, document: { documentElement: root, title: 'Preflight',
      getElementById: id => id === 'top' ? top : null,
      querySelector: q => q === '[data-dl-list]' ? list : null,
      querySelectorAll: q => ({ '[data-dl]': [button], '.ver': [version], '[data-zh],[data-zh-alt],[data-zh-label]': [button, list] }[q] ?? []) } });
  await Promise.resolve();
  return { button, list, version };
}
test('Mac Client Hints choose the matching chip and both installers remain accessible', async () => {
  for (const [arch, url] of [['arm', '/arm64.dmg'], ['x86', '/x64.dmg']]) {
    const { button, list } = await render('MacIntel', arch, 'zh');
    assert.equal(button.getAttribute('href'), url);
    assert.match(button.innerHTML, /下载 macOS/);
    assert.match(list.innerHTML, /Apple 芯片/);
    assert.match(list.innerHTML, /Intel/);
    assert.match(list.innerHTML, /href="\/x64.dmg"/);
  }
});
test('Safari Intel UA alone does not select the wrong chip', async () => {
  const { button } = await render('MacIntel');
  assert.equal(button.getAttribute('href'), '#get');
  assert.equal(button.innerHTML, 'Choose Mac version');
});
test('Windows keeps its independent installer and version', async () => {
  const { button, version } = await render('Win32');
  assert.equal(button.getAttribute('href'), '/x64.exe');
  assert.equal(version.textContent, '1.0.0');
});
