/*
 * The whole application. One page, three states: first-run setup, home, results.
 * Everything the page needs arrives from GET /api/home in a single call.
 */
(() => {
  if (!API.token()) { location.href = "index.html"; return; }

  const state = { home: null, competitors: [], settings: null, polling: null };

  /* ---------------- theme ---------------- */

  // The chosen theme is remembered per browser; index.html and app.html both read
  // it before first paint so the page never flashes the wrong background.
  function applyTheme(theme) {
    document.documentElement.dataset.bsTheme = theme;
    el("theme-icon").textContent = theme === "dark" ? "☀" : "☾";
    try { localStorage.setItem("yci.theme", theme); } catch (e) { /* private mode */ }
  }
  applyTheme(document.documentElement.dataset.bsTheme === "dark" ? "dark" : "light");

  el("btn-theme").onclick = () =>
    applyTheme(document.documentElement.dataset.bsTheme === "dark" ? "light" : "dark");

  /* ---------------- helpers ---------------- */

  function guard(fn) {
    return async (...args) => {
      try {
        await fn(...args);
      } catch (err) {
        if (err instanceof API.AuthError) { location.href = "index.html"; return; }
        toast(err.message);
      }
    };
  }

  function inlineMsg(node, message, type = "danger") {
    node.innerHTML = message
      ? '<div class="alert alert-' + type + ' py-2 mb-0">' + esc(message) + "</div>"
      : "";
  }

  function busy(button, on, label) {
    button.disabled = on;
    if (on) {
      button.dataset.label = button.innerHTML;
      button.innerHTML = '<span class="spinner-border spinner-border-sm"></span> ' + (label || "Working…");
    } else if (button.dataset.label) {
      button.innerHTML = button.dataset.label;
    }
  }

  const num = (n) => (n == null ? "—" : Number(n).toLocaleString());

  /* ---------------- boot ---------------- */

  const boot = guard(async () => {
    state.home = await API.get("/api/home");
    state.competitors = state.home.competitors || [];
    el("user-email").textContent = state.home.user ? state.home.user.email : "";

    hide(el("loading"));
    if (state.home.setupComplete) {
      showHome();
    } else {
      showSetup();
    }
  });

  /* ---------------- setup wizard ---------------- */

  function showSetup() {
    hide(el("home"));
    show(el("setup"));
    goToStep(state.home.channel ? 2 : 1);
    renderSetupCompetitors();
  }

  function goToStep(n) {
    el("step-1").classList.toggle("hidden", n !== 1);
    el("step-2").classList.toggle("hidden", n !== 2);
    el("step-dot-1").classList.toggle("active", n === 1);
    el("step-dot-2").classList.toggle("active", n === 2);
  }

  el("setup-channel-save").onclick = guard(async () => {
    const url = el("setup-channel").value.trim();
    if (!url) { inlineMsg(el("setup-channel-msg"), "Paste a channel URL, @handle or ID — or skip."); return; }

    const btn = el("setup-channel-save");
    busy(btn, true, "Looking up channel…");
    try {
      const channel = await API.put("/api/channel", { channelUrl: url });
      state.home.channel = channel;
      inlineMsg(el("setup-channel-msg"), "");
      goToStep(2);
    } catch (err) {
      if (err instanceof API.AuthError) throw err;
      inlineMsg(el("setup-channel-msg"), err.message);
    } finally {
      busy(btn, false);
    }
  });

  el("setup-channel-skip").onclick = () => goToStep(2);

  el("setup-competitor-add").onclick = guard(async () => {
    const url = el("setup-competitor").value.trim();
    if (!url) return;
    const btn = el("setup-competitor-add");
    busy(btn, true, "Adding…");
    try {
      const c = await API.post("/api/competitors", { channelUrl: url });
      state.competitors.push(c);
      el("setup-competitor").value = "";
      inlineMsg(el("setup-competitor-msg"), "");
      renderSetupCompetitors();
    } catch (err) {
      if (err instanceof API.AuthError) throw err;
      inlineMsg(el("setup-competitor-msg"), err.message);
    } finally {
      busy(btn, false);
    }
  });

  function renderSetupCompetitors() {
    const box = el("setup-competitor-list");
    if (!state.competitors.length) {
      box.innerHTML = '<p class="text-muted small mb-0">No competitors added yet.</p>';
      return;
    }
    box.innerHTML = state.competitors.map((c) => `
      <div class="chip">
        <span>${esc(c.channelName || c.youtubeChannelId)}</span>
        <button class="btn-close btn-close-sm" data-id="${c.id}" title="Remove"></button>
      </div>`).join("");
    box.querySelectorAll("button[data-id]").forEach((b) => {
      b.onclick = guard(async () => {
        await API.del("/api/competitors/" + b.dataset.id);
        state.competitors = state.competitors.filter((c) => String(c.id) !== b.dataset.id);
        renderSetupCompetitors();
      });
    });
  }

  el("setup-mode").onchange = () => {
    const days = el("setup-mode").value === "LAST_N_DAYS";
    el("setup-value-label").textContent = days ? "Days" : "Videos";
    el("setup-value").value = days ? 30 : 15;
  };

  const finishSetup = guard(async () => {
    await API.put("/api/settings", {
      retrievalMode: el("setup-mode").value,
      retrievalValue: parseInt(el("setup-value").value, 10) || 15,
      webSearchEnabled: el("setup-websearch").checked,
    });
    state.home = await API.get("/api/home");
    state.competitors = state.home.competitors || [];

    if (!state.home.setupComplete) {
      // Both steps skipped — spec item 10: say so instead of generating nothing.
      inlineMsg(el("setup-competitor-msg"),
        "Add your channel or at least one competitor — there's nothing to generate ideas from yet.");
      return;
    }
    hide(el("setup"));
    showHome();
  });

  el("setup-finish").onclick = finishSetup;
  el("setup-competitor-skip").onclick = finishSetup;

  /* ---------------- home ---------------- */

  function showHome() {
    hide(el("setup"));
    show(el("home"));
    renderChannelCard();
    renderCompetitorCards();
    renderLatestRun();

    if (state.home.generating) resumeRun();
  }

  function renderChannelCard() {
    const c = state.home.channel;
    const box = el("home-channel");
    if (!c) {
      box.innerHTML = `
        <p class="text-muted mb-2">No channel connected.</p>
        <button class="btn btn-sm btn-outline-danger" id="add-channel-inline">Add your channel</button>`;
      el("add-channel-inline").onclick = openSettings;
      return;
    }
    box.innerHTML = `
      <div class="channel-name">${esc(c.channelName || c.youtubeChannelId)}</div>
      <div class="text-muted small mb-2">${num(c.subscriberCount)} subscribers</div>
      <div class="small text-muted">${num(c.storedVideos)} videos · ${num(c.storedComments)} comments read</div>`;
  }

  function renderCompetitorCards() {
    const box = el("home-competitors");
    if (!state.competitors.length) {
      box.innerHTML = `
        <p class="text-muted mb-2">No competitor channels yet.</p>
        <button class="btn btn-sm btn-outline-danger" id="add-comp-inline">Add a competitor</button>`;
      el("add-comp-inline").onclick = openSettings;
      return;
    }
    box.innerHTML = '<div class="d-flex flex-wrap gap-2">' + state.competitors.map((c) => `
      <div class="competitor-card">
        <div class="channel-name">${esc(c.channelName || c.youtubeChannelId)}</div>
        <div class="small text-muted">${num(c.storedVideos)} videos · ${num(c.storedComments)} comments</div>
      </div>`).join("") + "</div>";
  }

  function renderLatestRun() {
    const run = state.home.latestRun;
    if (!run || !run.result) {
      show(el("no-results"));
      hide(el("result-box"));
      return;
    }
    showResult(run);
  }

  function showResult(run) {
    hide(el("no-results"));
    show(el("result-box"));
    el("result-body").innerHTML = renderMarkdown(run.result);
    el("result-meta").textContent =
      (run.model || "model") + (run.webSearchUsed ? " · web search" : "") +
      (run.completedAt ? " · " + new Date(run.completedAt).toLocaleString() : "");
    el("prompt-text").textContent = run.prompt || "(prompt unavailable)";
    el("btn-view-prompt").disabled = !run.prompt;

    // Be explicit when the ideas are not web-grounded even though the user asked
    // for it — the free search provider throttles aggressively.
    const wanted = state.home.settings && state.home.settings.webSearchEnabled;
    el("websearch-note").classList.toggle("hidden", !(wanted && !run.webSearchUsed));
  }

  /* ---------------- generation ---------------- */

  el("btn-generate").onclick = guard(async () => {
    const btn = el("btn-generate");
    busy(btn, true, "Starting…");
    try {
      const job = await API.post("/api/ideas/generate");
      startPolling(job.runId);
    } catch (err) {
      if (err instanceof API.AuthError) throw err;
      toast(err.message);
      busy(btn, false);
    }
  });

  /** Reattach to a run that was already in flight when the page loaded. */
  const resumeRun = guard(async () => {
    const history = await API.get("/api/ideas/history");
    const active = (history || []).find((r) => r.status === "QUEUED" || r.status === "RUNNING");
    if (active) {
      busy(el("btn-generate"), true, "Working…");
      startPolling(active.id);
    }
  });

  function startPolling(runId) {
    show(el("progress-box"));
    setProgress(3, "Starting…");
    if (state.polling) clearInterval(state.polling);

    state.polling = setInterval(guard(async () => {
      const run = await API.get("/api/ideas/runs/" + runId);
      setProgress(run.progress, run.message);

      if (run.status === "COMPLETED") {
        stopPolling();
        showResult(run);
        toast("Your ideas are ready.", "success");
        state.home = await API.get("/api/home");
        state.competitors = state.home.competitors || [];
        renderChannelCard();
        renderCompetitorCards();
      } else if (run.status === "FAILED") {
        stopPolling();
        toast(run.errorMessage || "Idea generation failed.");
      }
    }), 2000);
  }

  function stopPolling() {
    if (state.polling) clearInterval(state.polling);
    state.polling = null;
    hide(el("progress-box"));
    busy(el("btn-generate"), false);
  }

  function setProgress(pct, message) {
    el("progress-bar").style.width = Math.max(3, pct || 0) + "%";
    el("progress-msg").textContent = message || "Working…";
  }

  /* ---------------- prompt panel ---------------- */

  el("btn-view-prompt").onclick = () => {
    new bootstrap.Offcanvas(el("prompt-panel")).show();
  };

  el("btn-copy-prompt").onclick = async () => {
    try {
      await navigator.clipboard.writeText(el("prompt-text").textContent);
      toast("Prompt copied.", "success");
    } catch (e) {
      toast("Could not copy — select the text manually.");
    }
  };

  /* ---------------- settings ---------------- */

  const openSettings = guard(async () => {
    const [settings, competitors, health] = await Promise.all([
      API.get("/api/settings"),
      API.get("/api/competitors"),
      API.get("/api/health"),
    ]);
    state.competitors = competitors;
    state.settings = settings;

    el("set-channel").value = state.home.channel ? (state.home.channel.channelUrl || "") : "";
    el("set-mode").value = settings.retrievalMode;
    el("set-value").value = settings.retrievalValue;
    el("set-own-comments").value = settings.ownCommentLimit;
    el("set-comp-comments").value = settings.competitorCommentLimit;
    el("set-websearch").checked = !!settings.webSearchEnabled;
    el("set-notify").checked = !!settings.notifyEnabled;
    el("set-notify-freq").value = settings.notifyFrequency;
    el("set-notify-email").value = settings.notifyEmail || (state.home.user ? state.home.user.email : "");
    el("set-value-label").textContent = settings.retrievalMode === "LAST_N_DAYS" ? "Days" : "Videos";
    el("mail-warning").classList.toggle("hidden", !!health.emailConfigured);

    renderApiPane(settings);

    inlineMsg(el("settings-msg"), "");
    inlineMsg(el("set-channel-msg"), "");
    ["set-yt-msg", "set-llm-msg", "set-search-msg"].forEach((id) => inlineMsg(el(id), ""));
    renderSettingsCompetitors();
    new bootstrap.Modal(el("settings-modal")).show();
  });

  /* ---------------- API access ---------------- */

  /**
   * Fills the API pane. Saved keys are never sent back by the server, so the
   * inputs stay empty and what is stored is described next to them instead:
   * either a masked preview of the user's own key or the server's fallback.
   */
  function renderApiPane(settings) {
    const d = settings.defaults || {};

    el("set-yt-key").value = "";
    el("set-yt-status").innerHTML = keyStatus(
      settings.youtubeApiKeySet, settings.youtubeApiKeyPreview,
      d.youtubeConfigured ? "Using the server's key" : "No key anywhere — runs will fail");

    el("set-llm-key").value = "";
    el("set-llm-base").value = settings.llmBaseUrl || "";
    el("set-llm-model").value = settings.llmModel || "";
    el("set-llm-base").placeholder = d.llmBaseUrl || "https://api.openai.com/v1";
    el("set-llm-model").placeholder = d.llmModel || "gpt-4o-mini";
    el("set-llm-status").innerHTML = keyStatus(
      settings.llmApiKeySet, settings.llmApiKeyPreview,
      d.llmConfigured ? "Using the server's model key" : "No model key anywhere — runs will fail");

    el("set-search-provider").value = settings.webSearchProvider || "";
    el("set-searxng-url").value = settings.searxngBaseUrl || "";
    el("set-searxng-url").placeholder = d.webSearchBaseUrl || "http://searxng:8080";
    el("set-search-status").innerHTML =
      '<span class="pill pill-muted">Server default: ' + esc(d.webSearchProvider || "none")
      + (d.webSearchBaseUrl ? " at " + esc(d.webSearchBaseUrl) : "") + "</span>";

    el("set-secret-note").innerHTML = d.secretsEncrypted
      ? "🔒 Keys you save are encrypted before they are written to the database."
      : "⚠ The server has no <code>APP_SECRET_KEY</code>, so keys saved here are stored as typed. "
        + "Set one to encrypt them at rest.";
  }

  function keyStatus(isSet, preview, fallbackText) {
    return isSet
      ? '<span class="pill pill-ok">Your key ' + esc(preview) + "</span>"
      : '<span class="pill pill-muted">' + esc(fallbackText) + "</span>";
  }

  /** POST /api/settings/test/<target> — always answers, never throws for a bad key. */
  function wireTest(buttonId, target, msgId) {
    el(buttonId).onclick = guard(async () => {
      const btn = el(buttonId);
      busy(btn, true, "Testing…");
      inlineMsg(el(msgId), "");
      try {
        // Test what is *saved*, so save anything typed in first.
        await saveSettings({ silent: true });
        const res = await API.post("/api/settings/test/" + target);
        inlineMsg(el(msgId), res.message + (res.detail ? " " + res.detail : ""),
                  res.ok ? "success" : "danger");
      } catch (err) {
        if (err instanceof API.AuthError) throw err;
        inlineMsg(el(msgId), err.message);
      } finally {
        busy(btn, false);
      }
    });
  }

  wireTest("set-yt-test", "youtube", "set-yt-msg");
  wireTest("set-llm-test", "llm", "set-llm-msg");
  wireTest("set-search-test", "websearch", "set-search-msg");

  /** Clearing sends an empty string, which is what the API reads as "use the default". */
  function wireClear(buttonId, field, msgId) {
    el(buttonId).onclick = guard(async () => {
      const patch = {};
      patch[field] = "";
      state.settings = await API.put("/api/settings", patch);
      renderApiPane(state.settings);
      inlineMsg(el(msgId), "Cleared — the server's configuration is used again.", "success");
    });
  }

  wireClear("set-yt-clear", "youtubeApiKey", "set-yt-msg");
  wireClear("set-llm-clear", "llmApiKey", "set-llm-msg");

  el("btn-settings").onclick = openSettings;

  el("set-mode").onchange = () => {
    el("set-value-label").textContent = el("set-mode").value === "LAST_N_DAYS" ? "Days" : "Videos";
  };

  el("set-channel-save").onclick = guard(async () => {
    const url = el("set-channel").value.trim();
    if (!url) { inlineMsg(el("set-channel-msg"), "Enter a channel URL, @handle or ID."); return; }
    const btn = el("set-channel-save");
    busy(btn, true, "Saving…");
    try {
      state.home.channel = await API.put("/api/channel", { channelUrl: url });
      inlineMsg(el("set-channel-msg"), "Channel saved.", "success");
      renderChannelCard();
    } catch (err) {
      if (err instanceof API.AuthError) throw err;
      inlineMsg(el("set-channel-msg"), err.message);
    } finally {
      busy(btn, false);
    }
  });

  el("set-channel-remove").onclick = guard(async () => {
    await API.del("/api/channel");
    state.home.channel = null;
    el("set-channel").value = "";
    inlineMsg(el("set-channel-msg"), "Channel removed.", "success");
    renderChannelCard();
  });

  el("set-competitor-add").onclick = guard(async () => {
    const url = el("set-competitor").value.trim();
    if (!url) return;
    const btn = el("set-competitor-add");
    busy(btn, true, "Adding…");
    try {
      const c = await API.post("/api/competitors", { channelUrl: url });
      state.competitors.push(c);
      el("set-competitor").value = "";
      inlineMsg(el("settings-msg"), "");
      renderSettingsCompetitors();
      renderCompetitorCards();
    } catch (err) {
      if (err instanceof API.AuthError) throw err;
      inlineMsg(el("settings-msg"), err.message);
    } finally {
      busy(btn, false);
    }
  });

  function renderSettingsCompetitors() {
    const box = el("set-competitor-list");
    if (!state.competitors.length) {
      box.innerHTML = '<p class="text-muted small mb-0">No competitor channels.</p>';
      return;
    }
    box.innerHTML = state.competitors.map((c) => `
      <div class="d-flex align-items-center justify-content-between border-bottom py-2">
        <div>
          <div>${esc(c.channelName || c.youtubeChannelId)}</div>
          <div class="text-muted small">${num(c.storedVideos)} videos · ${num(c.storedComments)} comments</div>
        </div>
        <button class="btn btn-sm btn-outline-secondary" data-id="${c.id}">Remove</button>
      </div>`).join("");

    box.querySelectorAll("button[data-id]").forEach((b) => {
      b.onclick = guard(async () => {
        await API.del("/api/competitors/" + b.dataset.id);
        state.competitors = state.competitors.filter((c) => String(c.id) !== b.dataset.id);
        renderSettingsCompetitors();
        renderCompetitorCards();
      });
    });
  }

  /**
   * One PUT with the whole panel.
   *
   * The two key inputs are the exception: an empty box means "leave whatever is
   * stored alone" (null), not "clear it" — clearing is what the Clear buttons do.
   * Every other field is sent as typed, so emptying one falls back to the server.
   */
  const saveSettings = async ({ silent = false } = {}) => {
    const payload = {
      retrievalMode: el("set-mode").value,
      retrievalValue: parseInt(el("set-value").value, 10),
      ownCommentLimit: parseInt(el("set-own-comments").value, 10),
      competitorCommentLimit: parseInt(el("set-comp-comments").value, 10),
      webSearchEnabled: el("set-websearch").checked,
      notifyEnabled: el("set-notify").checked,
      notifyFrequency: el("set-notify-freq").value,
      notifyEmail: el("set-notify-email").value.trim(),
      llmBaseUrl: el("set-llm-base").value.trim(),
      llmModel: el("set-llm-model").value.trim(),
      webSearchProvider: el("set-search-provider").value,
      searxngBaseUrl: el("set-searxng-url").value.trim(),
    };
    const ytKey = el("set-yt-key").value.trim();
    const llmKey = el("set-llm-key").value.trim();
    if (ytKey) payload.youtubeApiKey = ytKey;
    if (llmKey) payload.llmApiKey = llmKey;

    state.settings = await API.put("/api/settings", payload);
    renderApiPane(state.settings);

    state.home = await API.get("/api/home");
    state.competitors = state.home.competitors || [];
    renderChannelCard();
    renderCompetitorCards();
    updateGenerateHint();
    if (!silent) inlineMsg(el("settings-msg"), "Settings saved.", "success");
  };

  el("settings-save").onclick = guard(async () => {
    const btn = el("settings-save");
    busy(btn, true, "Saving…");
    try {
      await saveSettings();
    } catch (err) {
      if (err instanceof API.AuthError) throw err;
      inlineMsg(el("settings-msg"), err.message);
    } finally {
      busy(btn, false);
    }
  });

  function updateGenerateHint() {
    const hasSources = !!state.home.channel || state.competitors.length > 0;
    el("btn-generate").disabled = !hasSources;
    el("generate-hint").textContent = hasSources
      ? "Reads your channels, analyses the comments, then asks the model."
      : "Add your channel or a competitor in Settings first.";
  }

  el("settings-modal").addEventListener("hidden.bs.modal", updateGenerateHint);

  /* ---------------- session ---------------- */

  el("btn-logout").onclick = async () => {
    try { await API.post("/api/auth/logout"); } catch (e) { /* signing out regardless */ }
    API.setToken(null);
    location.href = "index.html";
  };

  boot().then(() => { if (state.home && state.home.setupComplete) updateGenerateHint(); });
})();
