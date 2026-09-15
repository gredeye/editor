const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('docs/site.js', 'utf8');
async function render(response) {
  const nodes = {};
  for (const id of ['download', 'release-status', 'version', 'notes']) {
    nodes[id] = {textContent: '', attrs: {}, setAttribute(k,v){this.attrs[k]=v;}, removeAttribute(k){delete this.attrs[k];}};
  }
  nodes.download.attrs.href = 'https://github.com/gredeye/editor/releases/latest/download/Vynox.apk';
  await vm.runInNewContext(source, {
    document: {getElementById: id => nodes[id]},
    fetch: async () => response,
    AbortSignal, Date
  });
  return nodes;
}
test('No release disables the download instead of claiming an APK exists', async () => {
  const n=await render({status:404});
  assert.equal(n.download.attrs['aria-disabled'],'true');
  assert.equal(n.download.attrs.href,undefined);
});
test('Release metadata uses a stable URL and literal text, not HTML', async () => {
  const n=await render({ok:true,status:200,json:async()=>({tag_name:'v0.1.0',body:'<script>alert(1)</script>',published_at:'2026-09-15',assets:[{name:'Vynox.apk',size:1048576}]})});
  assert.match(n.download.attrs.href,/releases\/latest\/download\/Vynox.apk$/);
  assert.equal(n.notes.textContent,'<script>alert(1)</script>');
  assert.equal(n.version.textContent,'Vynox v0.1.0');
});
test('Rate limits retain the stable release link and explain the fallback', async () => {
  const n=await render({ok:false,status:403});
  assert.ok(n.download.attrs.href);
  assert.match(n['release-status'].textContent,/unavailable/);
});
test('A release missing the APK is not advertised as downloadable metadata', async () => {
  const n=await render({ok:true,status:200,json:async()=>({assets:[]})});
  assert.match(n['release-status'].textContent,/unavailable/);
});
