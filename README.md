# YouTube Content Intelligence

Tells a creator **what to make next**. It reads their own audience's comments and their
competitors' channels, works out what people are actually asking for, and hands all of
that evidence to an LLM as a single prompt.

The analysis is machinery, not output: the comment mining is never shown. The user sees
video ideas — and, if they want it, the exact prompt those ideas came from.

### Documentation

| | |
|---|---|
| **This file** | What the platform does, how the pieces fit, and how to start it locally. |
| [`PROJECT_GUIDE.md`](PROJECT_GUIDE.md) | The hands-on companion: code map, running without Docker, walking the API by hand, the full configuration reference, and a troubleshooting section indexed by symptom. |
| [`ml-service/README.md`](ml-service/README.md) | The demand-extraction service on its own — every endpoint, the request→topic pipeline in detail, and the two dependency sets. |
| [`AZURE_DEPLOYMENT.md`](AZURE_DEPLOYMENT.md) | Deploying to a server, by hand with `az`, inside what an Azure free account covers. |
| [`AZURE_TERRAFORM.md`](AZURE_TERRAFORM.md) | The same deployment as code — one `terraform apply`, one-command teardown. |

---

## 1. How it works

```
Sign up ──▶ Setup (both steps skippable)
             1. your channel
             2. competitors + how much history to read
                    │
                    ▼
            ┌───── Get ideas ─────┐
            │                     │
   your channel                competitors
   videos + top 1000            video info (no ML)
   comments                     + top 200 comments each
            │                     │
            └──── demand analysis ┘        ← never shown to the user
                    │
            + live web search (optional)
                    │
                    ▼
              one prompt ──▶ LLM ──▶ video ideas
                                     (+ "View full prompt")
```

Skipping **both** setup steps leaves nothing to reason about, and the app says so
instead of asking the model to invent ideas.

## 2. Architecture

```
Browser (one page: Bootstrap + vanilla JS)
        │  REST on the SAME ORIGIN, bearer token
nginx  ── /api/* ──▶ Spring Boot (auth · ingestion · prompt building · scheduler)
   │                    │            │              │
 static files         MySQL   Python ML API    SearXNG      LLM (OpenAI-compatible)
                              (FastAPI)        (private)    per user or server-wide
```

Four runnable services (`backend`, `ml-service`, `mysql`, `searxng`) plus the `frontend`
container, which serves the static files and proxies `/api` to the backend.

**No backend URL is baked into the page.** The frontend calls `/api/...` relative to
whatever origin it was opened on, and nginx (locally) or Caddy (on a server) forwards
that to Spring Boot. Nothing to configure, nothing to re-point when the host changes —
see [`frontend/js/config.js`](frontend/js/config.js) for the one development case that
needs an override.

## 3. Demand analysis — the part that matters

Explicit asks are the strongest signal a comment section produces, so the pipeline is
rule-first rather than clustering-first:

1. **Match request patterns.** "make a video on X", "I want to know about X", "can you
   cover X", "video idea: X", "waiting for X", … Each pattern captures the *subject* (X),
   not the boilerplate around it, and carries a weight by how unambiguous it is.
2. **Weight by agreement.** A request with 100 likes counts for more than one with none.
3. **Cluster paraphrases** semantically, so "kafka consumer groups" and "consumer group
   rebalancing" become one topic.
4. **Label from content words only.** Filler is stripped aggressively — an earlier version
   of this app produced topics literally named *"To / The"*, because the topic model had
   no stopword list on its labelling path.

Two rules keep the output honest:

- **Multi-word only** for the weaker "people keep mentioning this" signal. A bare
  "Price" or "Linus" is not a video topic — and it poisons the web search built from it
  ("Linus" returns *Linux* articles; "Price" returns cost-of-living listicles).
- **Singular/plural and word-order collapse**, so "Cable"/"Cables" cannot each take a slot.

Everything lands in [`ml-service/app/demand.py`](ml-service/app/demand.py) and
`stopwords.py`; [`ml-service/README.md`](ml-service/README.md) walks the pipeline
step by step, including what each stage rejects and why.

## 4. Web search — off by default

**Web search is opt-in per account.** The evidence that makes this app useful is the
creator's own comment section; search results are the one input nobody chose, and a
smaller model tends to follow them rather than the audience — answering about whatever the
web happens to be discussing instead of what viewers asked for. Turn it on in
**Settings → Reading** when ideas feel stale, off again when they feel off-topic.

When it is on: the local model can't be trusted to run a tool-calling loop, so the backend
does the searching and passes results in as plain context. Queries come from topics the
audience *explicitly requested* first; vague ones only fill leftover slots.

- `searxng` (default) — a private [SearXNG](https://docs.searxng.org/) instance started
  by `docker compose`. It is a metasearch engine: it asks Google, Brave, DuckDuckGo,
  Wikipedia and friends under its own name and merges the answers. Because the instance
  is yours, nothing throttles the app for searching four times a minute. Configured in
  [`deploy/searxng/`](deploy/searxng/) — `json` output on, bot limiter off, both needed
  for a server-side caller — and browsable on <http://localhost:8888>.
- `duckduckgo` — no API key and no container, but unofficial: it throttles repeat callers
  and then answers with an empty challenge page. Kept as the automatic fallback when
  SearXNG returns nothing (`WEB_SEARCH_FALLBACK`), and selectable on its own.
- `none` — off.

Each user can pick their own provider, or point at their own SearXNG, under
**Settings → API access** — that choice only takes effect once search itself is switched
on. Whatever the provider, a failed search is never fatal: the run continues on channel
data alone and the UI says so.

The SearXNG container starts with the rest of the stack so that enabling the toggle just
works; `docker compose up -d --scale searxng=0` skips it if you never intend to.

## 5. Getting started

```bash
cp .env.example .env       # set YOUTUBE_API_KEY, your LLM settings and APP_SECRET_KEY
docker compose up --build
```

Then open **http://localhost:8081** and create an account.

Services: MySQL (3306), ML service (8000), backend (8080), frontend (8081), SearXNG (8888).

The server-wide `YOUTUBE_API_KEY` and `LLM_*` values are only defaults — an account with
neither can still be created, and the app will ask for its own keys under
**Settings → API access** (section 7.1).

### Without Docker

1. Start MySQL and load `database/schema.sql`.
2. `cd ml-service && python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt && uvicorn app.main:app --port 8000`
3. `cd backend && mvn spring-boot:run`
4. Serve `frontend/` with any static server — and because a bare static server does not
   proxy `/api`, uncomment the one line in `frontend/js/config.js`:
   `window.YCI_API_BASE = "http://localhost:8080";`
5. Optional: `docker compose up -d searxng` for web search, then set
   `WEB_SEARCH_BASE_URL=http://localhost:8888`.

### On a server

`deploy/azure/docker-compose.azure.yml` is the deployment shape: no MySQL container (a
managed database instead), Caddy terminating TLS in front of the same nginx, and the ML
service built from `Dockerfile.lite` — TF-IDF only, no `torch`, 711 MB instead of 10.4 GB,
which is what makes the whole stack fit on a 1 GiB host. Explicit asks are found by regex
either way; only the clustering of paraphrases degrades. Walkthroughs:
[`AZURE_DEPLOYMENT.md`](AZURE_DEPLOYMENT.md) by hand,
[`AZURE_TERRAFORM.md`](AZURE_TERRAFORM.md) as code.

## 6. Choosing a model

Anything speaking the OpenAI `/chat/completions` schema works. Two things matter more
than raw quality on a local machine:

- **Avoid "thinking" models.** A reasoning model spends minutes on hidden tokens before
  answering — `qwen3.5:4b` took 75 s to reply "hi" on CPU. `qwen2.5:3b-instruct` answers
  the full prompt in ~30 s on the same hardware.
- **Generation is a background job.** `LLM_TIMEOUT_SECONDS` defaults to 900 and the
  frontend polls for progress, so a slow model is fine — a truncated answer is not,
  which is why no `max_tokens` is sent.

For host Ollama from inside Docker, use `http://host.docker.internal:11434/v1` and make
sure Ollama listens on all interfaces (`OLLAMA_HOST=0.0.0.0`).

## 7. Configuration

See [`.env.example`](.env.example). Secrets come only from the environment.

| Variable | Purpose |
|---|---|
| `APP_SECRET_KEY` | Encrypts the API keys users save in Settings. Blank = stored as typed. |
| `YOUTUBE_API_KEY` | Default YouTube Data API v3 key, used by accounts with none of their own. |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | Default model that writes the ideas. |
| `LLM_TIMEOUT_SECONDS` / `LLM_MAX_PROMPT_CHARS` | Slow-model ceiling, prompt budget. |
| `WEB_SEARCH_PROVIDER` / `WEB_SEARCH_BASE_URL` | Which provider, *if* a user enables search: `searxng` (default), `duckduckgo` or `none`. |
| `WEB_SEARCH_FALLBACK` | Try DuckDuckGo when SearXNG comes back empty. |
| `SEARXNG_SECRET` / `SEARXNG_BASE_URL` | Session secret and self-address for the bundled instance. |
| `SMTP_*`, `NOTIFY_*`, `APP_URL` | Email digests. Blank `SMTP_HOST` = save the preference, send nothing. |
| `AUTH_TOKEN_TTL_DAYS` | Session length. |

Per-channel volumes (last N videos vs last N days, comment budgets, web search on/off)
are **per-user settings** in the app, not environment variables, and persist across runs.

### 7.1 Bring your own API keys

Every account can override the server's configuration for itself under
**Settings → API access**:

| Field | Empty means | Filled in means |
|---|---|---|
| YouTube API key | the server's `YOUTUBE_API_KEY` | your key, your daily quota |
| LLM base URL / model / key | the server's `LLM_*` | your provider, your model, your bill |
| Web search provider + SearXNG URL | the server's provider | your choice, your instance (search stays off until enabled in Reading) |

Three details make this safe to use:

- **Keys are write-only.** A saved key is never sent back to the browser; the panel shows
  a masked preview (`AIza…7f2c`) and a *Clear* button that restores the server default.
- **Keys are encrypted at rest** with AES-GCM under `APP_SECRET_KEY`. Without that
  variable they are stored as typed and the backend warns about it at startup — and the
  Settings panel tells the user the same thing.
- **Each field has a Test button.** It makes the smallest real call the provider offers
  (one YouTube channel lookup, a one-word completion, a single search) so a wrong key
  surfaces in seconds instead of three minutes into a run.

Runs are also refused up front, with a message pointing at Settings, when neither the user
nor the server has a usable YouTube key or model.

## 8. API

```
POST   /api/auth/signup | /login | /logout      GET /api/auth/me
GET    /api/home                 everything the page needs in one call
GET    /api/channel              PUT (set/replace)   DELETE
GET    /api/competitors          POST (add)          DELETE /{id}
GET    /api/settings             PUT
POST   /api/settings/test/{youtube|llm|websearch}   check the saved credentials
POST   /api/ideas/generate       queue a run  ->  { runId }
GET    /api/ideas/runs/{id}      poll: QUEUED | RUNNING | COMPLETED | FAILED
GET    /api/ideas/latest | /history
GET    /api/health               unauthenticated
```

Everything except `/api/auth/signup`, `/api/auth/login` and `/api/health` needs
`Authorization: Bearer <token>`.

## 9. Authentication

Email + password, BCrypt-hashed, with opaque server-side bearer tokens (revocable by
deleting a row; no JWT). Login failures are deliberately indistinguishable between
"unknown email" and "wrong password".

An earlier version trusted a client-supplied `X-User-Id` header, which let any caller act
as any user. That header no longer exists.

## 10. Email digests

Opt in per user, daily or weekly. An hourly scheduler picks up whoever is due, generates
a fresh set of ideas and emails them. With `SMTP_HOST` unset, Spring creates no mail
sender and the scheduler logs and skips — the preference is still saved, and the settings
panel says so.

## 11. Testing

```bash
cd backend && mvn test                          # prompt rules, per-user API config, context wiring
cd ml-service && pip install pytest && pytest   # preprocessing, models, topics
```

`pytest` is in neither requirements file, so a fresh virtualenv needs that extra install.

The context test boots the app directly rather than via `@SpringBootTest`, because a bean
cycle (`WebConfig → AuthInterceptor → AuthService → PasswordEncoder`) is exactly the kind
of failure the unit tests cannot see.

## 12. Limitations

- YouTube quota applies. Comment budgets are spread across a channel's videos so one busy
  video can't consume the lot.
- Comments come back in YouTube's "relevance" order — that's what makes them *top* comments.
- Videos with comments disabled are skipped silently.
- Demand analysis only works as well as the comment section: a channel whose audience
  never asks for anything yields mentions, not requests.
- The DuckDuckGo provider is unofficial and throttles; SearXNG is the default for that
  reason, and it in turn depends on the engines it queries staying reachable.
- The model writes the ideas; every number it cites has to come from the evidence, but
  nothing forces it to — read the prompt if an idea looks unmoored.

### Project structure

```
youtube-content-intelligence/
├── backend/        Spring Boot (auth, ingestion, prompt building, scheduler)
├── ml-service/     FastAPI demand analysis (demand.py, stopwords.py) — has its own README
├── frontend/       index.html (auth) + app.html (the single page)
├── database/       schema.sql + migrations/
├── deploy/         nginx.conf (static + /api proxy), searxng/, azure/ (compose + terraform)
├── docker-compose.yml
├── .env.example
├── PROJECT_GUIDE.md         running it, configuring it, troubleshooting it
└── AZURE_DEPLOYMENT.md      deploying it — by hand, and AZURE_TERRAFORM.md as code
```
