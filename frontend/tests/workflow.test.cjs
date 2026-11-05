/* Run with node --test; jsdom is a test-only dependency (see README). */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const root = path.join(__dirname, '..');
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
const channel = { id: 1, channelName: 'Own', youtubeChannelId: 'UC' + 'a'.repeat(22) };
const suggestedId = 'UC' + 'b'.repeat(22);
const suggestion = { name: 'Verified Channel', channel_id: suggestedId, category: 'Programming', reason: 'Backend tutorials' };
const idea = { id: 0, title: 'Build Kafka', description: 'Hands-on application', reason: 'Audience demand',
  evidence: 'Requests', angle: 'Practical', differentiation: 'Tests', outline: ['Setup', 'Build'],
  related_videos: [{ video_id: 'abcdefghijk', title: 'Kafka Tutorial', channel_name: 'Rival' }] };

async function boot({ own = channel, complete = true, summaryStatus = 'available', setup = false, generation = false } = {}) {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'app.html'), 'utf8'), { url: 'http://localhost/app.html', runScripts: 'outside-only' });
  const w = dom.window;
  w.localStorage.setItem('yci.token', 'token');
  w.bootstrap = { Modal: class { show() {} }, Offcanvas: class { show() {} }, Toast: class { show() {} } };
  let resolveSummary;
  const summaryWait = new Promise(resolve => { resolveSummary = resolve; });
  const requests = [];
  const competitors = [];
  w.fetch = async (url, options) => {
    requests.push({ url, method: options.method });
    let response = null;
    let status = 200;
    if (url === '/api/home') response = { user: { email: 'test@example.com' }, channel: own,
      setupComplete: !setup, competitors, settings: {}, generating: false,
      latestRun: complete ? { id: 5, result: 'legacy', recommendations: { ideas: [JSON.parse(JSON.stringify(idea))], what_to_make_first: 'Kafka' } } : null };
    else if (url === '/api/ideas/generate') response = { runId: 9, status: 'QUEUED' };
    else if (url === '/api/ideas/runs/9') { await summaryWait; response = { status: 'FAILED', errorMessage: 'Test finished' }; }
    else if (url.endsWith('/suggestions')) response = null;
    else if (url.endsWith('/suggest-competitors')) response = { detected_niche: 'Programming', suggested_channels: [suggestion] };
    else if (url === '/api/competitors' && options.method === 'POST') response = { id: 2, channelName: suggestion.name, youtubeChannelId: suggestedId };
    else if (url === '/api/channel' && options.method === 'PUT') { own = channel; response = channel; }
    else if (url.endsWith('/transcript-summary')) {
      await summaryWait;
      if (summaryStatus === 'error') { status = 503; response = { message: 'Subtitle retrieval failed temporarily. Please try again later.' }; }
      else if (summaryStatus === 'unavailable') response = { status: 'unavailable', message: 'No transcript available' };
      else response = { status: 'available', idea_title: idea.title, summary: 'Actual subtitle summary', what_to_speak: ['Explain producers'],
        important_concepts: ['Topics'], suggested_structure: ['Introduction'], sources: idea.related_videos };
    }
    return { ok: status === 200, status, text: async () => response ? JSON.stringify(response) : '' };
  };
  vm.runInContext(fs.readFileSync(path.join(root, 'js/api.js'), 'utf8'), dom.getInternalVMContext());
  vm.runInContext(fs.readFileSync(path.join(root, 'js/app.js'), 'utf8'), dom.getInternalVMContext());
  for (let i = 0; i < 5; i++) await tick();
  return { w, requests, finish: resolveSummary, close: () => dom.window.close() };
}

test('verified suggestions render and Add Competitor reuses existing endpoint', async () => {
  const app = await boot();
  try {
    const box = app.w.document.getElementById('home-suggestions');
    assert.match(box.textContent, /Verified Channel/);
    const button = box.querySelector('[data-suggestion]');
    button.click(); await tick(); await tick();
    assert(app.requests.some(r => r.url === '/api/competitors' && r.method === 'POST'));
    assert.equal(box.querySelector('[data-suggestion]').textContent, 'Added');
    assert.equal(box.querySelector('[data-suggestion]').disabled, true);
  } finally { app.close(); }
});

test('skipped setup hides suggestions, makes no discovery requests, and permits manual competitors', async () => {
  const app = await boot({ own: null, complete: false, setup: true });
  try {
    app.w.document.getElementById('setup-channel-skip').click(); await tick();
    assert(!app.requests.some(r => /suggest/.test(r.url)));
    assert(app.w.document.getElementById('setup-suggestions').classList.contains('hidden'));
    app.w.document.getElementById('setup-competitor').value = '@manual';
    app.w.document.getElementById('setup-competitor-add').click(); await tick(); await tick();
    assert(app.requests.some(r => r.url === '/api/competitors' && r.method === 'POST'));
    assert(!app.requests.some(r => /suggest/.test(r.url)));
  } finally { app.close(); }
});

test('adding own channel later automatically enables suggestions', async () => {
  const app = await boot({ own: null, complete: false, setup: true });
  try {
    app.w.document.getElementById('setup-channel').value = '@own';
    app.w.document.getElementById('setup-channel-save').click();
    for (let i = 0; i < 5; i++) await tick();
    assert(app.requests.some(r => r.url.endsWith('/suggest-competitors')));
    assert.match(app.w.document.getElementById('setup-suggestions').textContent, /Verified Channel/);
  } finally { app.close(); }
});

for (const status of ['available', 'unavailable', 'error']) {
  test('related videos and transcript summary UI: ' + status, async () => {
    const app = await boot({ summaryStatus: status });
    try {
      const card = app.w.document.querySelector('[data-idea]');
      assert.match(card.textContent, /Kafka Tutorial.*Rival/s);
      const button = card.querySelector('[data-summary]');
      assert.equal(button.textContent, 'Get Transcript Summary');
      button.click(); await tick();
      assert(button.disabled);
      assert.match(button.textContent, /Fetching/);
      app.finish(); await tick(); await tick();
      const text = card.querySelector('[data-summary-result]').textContent;
      assert(!button.disabled);
      if (status === 'available') { assert.match(text, /Transcript Summary/); assert.match(text, /Explain producers/); assert.match(text, /Sources/); }
      else if (status === 'unavailable') assert.equal(text, 'No transcript available');
      else { assert.match(text, /temporarily/); assert(!text.includes('No transcript available')); }
    } finally { app.close(); }
  });
}

test('opening settings cannot enable generation while a run is active', async () => {
  const app = await boot({ generation: true });
  try {
    const button = app.w.document.getElementById('btn-generate');
    button.click(); await tick(); await tick();
    app.w.document.getElementById('settings-modal').dispatchEvent(new app.w.Event('hidden.bs.modal'));
    assert(button.disabled, 'an active run must keep generation disabled');
  } finally { app.finish(); app.close(); }
});

test('slow progress responses never cause overlapping polling requests', async () => {
  const app = await boot({ generation: true });
  try {
    app.w.document.getElementById('btn-generate').click(); await tick();
    await new Promise(resolve => setTimeout(resolve, 4300));
    assert.equal(app.requests.filter(r => r.url === '/api/ideas/runs/9').length, 1);
  } finally { app.finish(); await tick(); app.close(); }
});
