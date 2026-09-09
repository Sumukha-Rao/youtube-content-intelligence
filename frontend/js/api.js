/* Shared API client + small UI helpers. Vanilla JS, no framework. */
const API = (() => {
  const TOKEN_KEY = "yci.token";

  /**
   * Where the API lives.
   *
   * Nothing is hardcoded: requests go to the origin the page was opened from,
   * where nginx (locally) or Caddy (on a server) proxies /api to Spring Boot. The
   * app therefore works on localhost, on a LAN address and behind a domain with
   * no edit and no field to fill in.
   *
   * js/config.js can set window.YCI_API_BASE to point somewhere else, which is
   * only needed when the page is served by something that does not proxy /api —
   * `python -m http.server`, or opening the file directly.
   */
  const base = () => String(window.YCI_API_BASE || "").trim().replace(/\/+$/, "");
  const token = () => localStorage.getItem(TOKEN_KEY);
  const origin = () => base() || window.location.origin;

  function setToken(t) {
    if (t) localStorage.setItem(TOKEN_KEY, t);
    else localStorage.removeItem(TOKEN_KEY);
  }

  /** Thrown for HTTP 401 so callers can bounce the user back to sign-in. */
  class AuthError extends Error {}

  async function request(method, path, body) {
    const headers = { "Content-Type": "application/json" };
    const t = token();
    if (t) headers["Authorization"] = "Bearer " + t;

    const opts = { method, headers };
    if (body !== undefined) opts.body = JSON.stringify(body);

    let res;
    try {
      res = await fetch(base() + path, opts);
    } catch (e) {
      throw new Error("Cannot reach the backend at " + origin() + ". Is it running?");
    }

    const text = await res.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = null; }
    }

    if (res.status === 401) {
      setToken(null);
      throw new AuthError((data && data.message) || "Your session expired. Please sign in again.");
    }
    if (!res.ok) {
      throw new Error((data && data.message) || res.status + " " + res.statusText);
    }
    return data;
  }

  return {
    AuthError,
    base, origin, token, setToken,
    get: (p) => request("GET", p),
    post: (p, b) => request("POST", p, b),
    put: (p, b) => request("PUT", p, b),
    del: (p) => request("DELETE", p),
  };
})();

/* ---- tiny DOM helpers ---- */
function el(id) { return document.getElementById(id); }
function show(node) { node && node.classList.remove("hidden"); }
function hide(node) { node && node.classList.add("hidden"); }
function esc(s) {
  return (s == null ? "" : String(s)).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function toast(message, type = "danger") {
  const box = el("toast-box");
  if (!box) { alert(message); return; }
  const n = document.createElement("div");
  n.className = "alert alert-" + type + " shadow-sm";
  n.textContent = message;
  box.appendChild(n);
  setTimeout(() => n.remove(), 6000);
}

/**
 * Minimal Markdown renderer for the model's answer.
 *
 * Deliberately dependency-free: the output shape is known (headings, bold,
 * italics, bullets, paragraphs) and everything is escaped before any tag is
 * added, so model output can never inject HTML.
 *
 * Two rules matter for the numbered idea list:
 *  - A blank line does NOT end a list. Markdown allows "loose" lists whose items
 *    are separated by blank lines; closing the <ol> on each one restarts the
 *    numbering, so every idea renders as "1.".
 *  - An <ol> starts at the number actually written, so a list beginning at 3
 *    stays at 3.
 */
function renderMarkdown(md) {
  if (!md) return "";
  const inline = (s) => esc(s)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>")
    .replace(/_([^_\n]+)_/g, "<em>$1</em>")
    .replace(/`([^`\n]+)`/g, "<code>$1</code>");

  const out = [];
  const stack = []; // open lists, outermost first: { type, indent }

  const closeDeeperThan = (indent) => {
    while (stack.length && stack[stack.length - 1].indent > indent) {
      out.push(`</${stack.pop().type}>`);
    }
  };
  const closeAll = () => {
    while (stack.length) out.push(`</${stack.pop().type}>`);
  };

  for (const raw of String(md).split(/\r?\n/)) {
    const line = raw.trimEnd();

    // A blank line separates blocks but never terminates a list.
    if (!line.trim()) continue;

    const heading = line.match(/^\s{0,3}(#{1,6})\s+(.*)$/);
    if (heading) {
      closeAll();
      const level = Math.min(heading[1].length + 1, 6); // demote h1 -> h2
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      continue;
    }

    if (/^\s{0,3}(---|\*\*\*|___)\s*$/.test(line)) {
      closeAll();
      out.push("<hr>");
      continue;
    }

    const item = line.match(/^(\s*)(?:([-*+])|(\d+)[.)])\s+(.*)$/);
    if (item) {
      const indent = item[1].length;
      const type = item[2] ? "ul" : "ol";
      const text = item[4];

      closeDeeperThan(indent);
      const top = stack[stack.length - 1];

      if (!top || indent > top.indent) {
        // A deeper indent opens a nested list inside the current item.
        const start = type === "ol" ? parseInt(item[3], 10) : 1;
        out.push(type === "ol" && start !== 1 ? `<ol start="${start}">` : `<${type}>`);
        stack.push({ type, indent });
      } else if (top.type !== type) {
        // Same level, different marker: replace the list.
        out.push(`</${stack.pop().type}>`);
        const start = type === "ol" ? parseInt(item[3], 10) : 1;
        out.push(type === "ol" && start !== 1 ? `<ol start="${start}">` : `<${type}>`);
        stack.push({ type, indent });
      }

      out.push(`<li>${inline(text)}</li>`);
      continue;
    }

    closeAll();
    out.push(`<p>${inline(line)}</p>`);
  }

  closeAll();
  return out.join("\n");
}
