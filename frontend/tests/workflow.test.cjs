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

async function boot({ own = channel, complete = true, summaryStatus = 'available', setup = false, generation = false, emailStatus = 200, apiSettings = {} } = {}) {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'app.html'), 'utf8'), { url: 'http://localhost/app.html', runScripts: 'outside-only' });
  const w = dom.window;
  w.localStorage.setItem('yci.token', 'token');
  w.bootstrap = { Modal: class { show() {} hide() {} }, Offcanvas: class { show() {} }, Toast: class { show() {} } };
  let resolveSummary;
  const summaryWait = new Promise(resolve => { resolveSummary = resolve; });
  const requests = [];
  const competitors = [];
  w.fetch = async (url, options) => {
    requests.push({ url, method: options.method, body: options.body });
    let response = null;
    let status = 200;
    if (url === '/api/home') response = { user: { email: 'test@example.com' }, channel: own,
      setupComplete: !setup, competitors, settings: {}, generating: false,
      latestRun: complete ? { id: 5, result: 'legacy', recommendations: { ideas: [JSON.parse(JSON.stringify(idea))], what_to_make_first: 'Kafka' } } : null };
    else if (url === '/api/ideas/generate') response = { runId: 9, status: 'QUEUED' };
    else if (url === '/api/ideas/runs/9') { await summaryWait; response = { status: 'FAILED', errorMessage: 'Test finished' }; }
    else if (url.endsWith('/email')) { await summaryWait; status = emailStatus; response = { message: status === 200 ? 'Ideas sent.' : 'Email delivery is not configured.' }; }
    else if (url === '/api/settings') {
      if (options.method === 'PUT' && JSON.parse(options.body).llmApiKey === '') apiSettings.llmApiKeySet = false;
      response = { retrievalMode: 'LAST_N_VIDEOS', retrievalValue: 15, ...apiSettings };
    }
    else if (url === '/api/health') response = { status: 'UP', emailConfigured: false };
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
      else if (status === 'unavailable') assert.match(text, /No transcript available/);
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

for (const emailStatus of [200, 503]) {
  test('email dialog sends displayed run, prevents duplicates and handles status ' + emailStatus, async () => {
    const app = await boot({ emailStatus });
    try {
      const d = app.w.document;
      d.getElementById('btn-send-mail').click();
      const input = d.getElementById('send-mail-email');
      assert.equal(input.value, 'test@example.com');
      input.value = 'recipient@example.com';
      d.getElementById('send-mail-submit').click(); await tick();
      assert(d.getElementById('send-mail-submit').disabled);
      assert(d.getElementById('btn-send-mail').disabled);
      d.getElementById('send-mail-form').dispatchEvent(new app.w.Event('submit', { cancelable: true }));
      await tick();
      const sends = app.requests.filter(r => r.url.endsWith('/email'));
      assert.equal(sends.length, 1);
      assert.equal(sends[0].url, '/api/ideas/runs/5/email');
      assert.deepEqual(JSON.parse(sends[0].body), { email: 'recipient@example.com' });
      app.finish(); await tick(); await tick();
      assert(!d.getElementById('send-mail-submit').disabled);
      assert(!input.disabled);
      if (emailStatus === 200) assert.match(d.getElementById('toast-box').textContent, /Ideas sent/);
      else assert.match(d.getElementById('send-mail-message').textContent, /not configured/);
    } finally { app.finish(); app.close(); }
  });
}

test('invalid recipient does not send mail', async () => {
  const app = await boot();
  try {
    const d = app.w.document;
    d.getElementById('btn-send-mail').click();
    d.getElementById('send-mail-email').value = 'invalid';
    d.getElementById('send-mail-submit').click(); await tick();
    assert(!app.requests.some(r => r.url.endsWith('/email')));
  } finally { app.close(); }
});

for (const custom of [true, false]) {
  test('model key status respects custom provider endpoint: ' + custom, async () => {
    const app = await boot({ apiSettings: {
      llmBaseUrl: custom ? 'https://other.example/v1' : 'https://default.example/v1/',
      defaults: { llmBaseUrl: 'https://default.example/v1', llmConfigured: true },
    }});
    try {
      app.w.document.getElementById('btn-settings').click(); await tick(); await tick();
      const status = app.w.document.getElementById('set-llm-status').textContent;
      assert.match(status, custom ? /Add a key/ : /server's model key/);
      app.w.document.getElementById('set-llm-clear').click(); await tick(); await tick();
      const message = app.w.document.getElementById('set-llm-msg').textContent;
      assert.match(message, custom ? /Add a key for your provider/ : /server's configuration is used again/);
    } finally { app.close(); }
  });
}
