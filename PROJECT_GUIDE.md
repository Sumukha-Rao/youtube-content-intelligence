# Project Guide — YouTube Content Intelligence

A hands-on companion to [`README.md`](README.md). The README describes *what the platform
does*; this guide covers **how it is put together, how to run it, and what to do when it
does not start**. Every command and output below was executed against this repository.

---

## 1. What runs where

Five containers. Three are application code, one is nginx, one is a search engine.

| Service | Image / stack | Port | Purpose |
|---|---|---|---|
| `yci-mysql` | `mysql:8.0` | 3306 | Persistence. Schema loaded from `database/schema.sql` on first boot. |
| `yci-ml-service` | Python 3.10 + FastAPI | 8000 | Audience demand extraction from comments. |
| `yci-backend` | Java 17 + Spring Boot 3.3.4 | 8080 | REST API, auth, ingestion, prompt building, scheduler, LLM calls. |
| `yci-searxng` | `searxng/searxng` | 8888 | Private metasearch instance for the web-search step. |
| `yci-frontend` | `nginx:alpine` | 8081 | Serves `frontend/` **and proxies `/api` to the backend**. |

Two external dependencies:

- **YouTube Data API v3** — required. Without a key (the server's, or the user's own from
  Settings → API access) no channel can be read.
- **An OpenAI-compatible LLM endpoint** — required to generate ideas. Also per-user.

Web search is optional and defaults to the bundled SearXNG.

### Ports and origins

Only **8081** matters to a user. The page fetches `/api/...` on its own origin and nginx
forwards it to the backend, so 8080 needs to be published only for direct curl-ing during
development. The same shape is used in production, where Caddy terminates TLS and routes
`/api/*` to the backend and everything else to nginx.

### Request flow

```
Browser (frontend :8081)
      │  fetch("/api/…") with Authorization: Bearer <token>   ← same origin, no host
      ▼
nginx (:8081)  ──  location /api/ → proxy_pass http://backend:8080
      ▼
Spring Boot (:8080)
      ├──► MySQL (:3306)               accounts, channels, comments, runs, saved API keys
      ├──► YouTube Data API            videos + top comments   (user's key or the server's)
      ├──► ML service (:8000)          /ml/demand — what the audience is asking for
      ├──► SearXNG (:8888) JSON        current context, DuckDuckGo as fallback
      └──► LLM /chat/completions       writes the ideas from the assembled evidence
```

Everything above the LLM is evidence gathering. The model only turns that evidence into
readable ideas, and the exact prompt is stored with each run so it can be inspected.

---

## 2. Code map

### `backend/src/main/java/com/yci/`

| Package | Contents |
|---|---|
| `controller/` | `Auth`, `Channel`, `Competitor`, `Settings`, `Idea`, `Home` — thin REST layer. |
| `security/` | `AuthInterceptor` (validates the bearer token), `@CurrentUser` + its argument resolver. A controller can never read the user id from a client header. |
| `service/` | `AuthService`, `UserSettingsService`, `ChannelService`, `CompetitorService`, `IngestService` (YouTube → DB), `PromptBuilder` (the prompt), `IdeaService` (the async run), `NotificationService`, `ConnectionTestService` (the Test buttons). |
| `service/credentials/` | `ApiConfigService` merges a user's saved keys over the server's environment and hands the result to whoever makes the call; `ApiConfig` is that resolved shape; `SecretCipher` is the AES-GCM encryption of stored keys. |
| `service/ml/` | `MlClient` — the HTTP client for the FastAPI service. |
| `service/ai/` | `LlmClient` — any OpenAI-compatible `/chat/completions` endpoint. The endpoint is a per-call argument, not a startup constant, because each user may point at their own. |
| `service/websearch/` | `WebSearchService` — SearXNG or DuckDuckGo, best-effort, per user. |
| `service/youtube/` | `YouTubeService` (Data API v3), `TranscriptService` (best-effort). Callers open a `YouTubeService.Api` session bound to one key, so quota is always spent against the right project. |
| `entity/` + `repository/` | JPA mirror of `database/schema.sql`. |
| `scheduler/` | `DigestScheduler` — hourly; emails whoever is due. |
| `config/` | `AppProperties` binds `app.*`; `WebConfig` does CORS, the interceptor and the resolver; `SecurityBeans` holds the password encoder. |
| `exception/` | Typed exceptions plus `GlobalExceptionHandler`. |

`SecurityBeans` exists purely to break a cycle: `WebConfig` → `AuthInterceptor` →
`AuthService` → `PasswordEncoder`. Declaring the encoder inside `WebConfig` stops the
application from starting.

### `ml-service/app/`

| File | Role |
|---|---|
| `main.py` | FastAPI app; warms the classifiers on startup so the first request is fast. |
| `demand.py` | **The core.** Request patterns, subject extraction, clustering, labelling. |
| `stopwords.py` | The vocabulary banned from labels, plus `topic_key()` for plural/word-order dedupe. |
| `preprocessing.py` | Normalization, spam/duplicate filtering, language detection. |
| `embeddings.py` | sentence-transformers when available, TF-IDF fallback otherwise. |
| `topics.py` | Generic topic discovery and similarity (kept; not on the idea path). |
| `models.py` / `train.py` | Sentiment + intent classifiers. Not used by the idea flow. |
| `schemas.py` | Every request and response model — the API contract in one file. |

The backend calls only `/health` and `/ml/demand`; the other six endpoints are standalone
and unused by the idea path. [`ml-service/README.md`](ml-service/README.md) documents all
of them, along with the request-to-topic pipeline step by step.

### `frontend/`

Two pages only: `index.html` (sign in / create account) and `app.html` (setup wizard,
home, results, settings). `js/api.js` holds the API client, the bearer token and the
Markdown renderer; `js/app.js` is the whole page; `css/styles.css` is a small token-based
theme layer over Bootstrap 5.3 that also supplies the dark mode.

There is **no backend URL in the frontend**. `API.base()` returns an empty string, so every
call is relative and lands on whichever origin served the page — nginx and Caddy both
proxy `/api` to Spring Boot. `js/config.js` exists for the single case that cannot work
that way (a bare static server such as `python -m http.server`), where uncommenting
`window.YCI_API_BASE` points the client elsewhere; it also then needs CORS, which the
backend allows by default.

The theme is remembered in `localStorage["yci.theme"]` and applied in a tiny inline script
before first paint, so a dark-mode reload never flashes white.

---

## 3. Running it

### 3.1 Docker (recommended)

```bash
cp .env.example .env      # then edit, see §4
docker compose up -d --build
```

First build is slow: the ML image installs `sentence-transformers` (10.4 GB image) and the
backend image runs a full Maven build. Later starts take seconds.

If that is too much for the machine, `ml-service/Dockerfile.lite` builds the same service
without the semantic stack — 711 MB, TF-IDF instead of embeddings, every endpoint still
working. It is what the server deployment uses; see `ml-service/README.md` §6.

```bash
curl http://localhost:8081/api/health      # through nginx — the path the browser uses
curl http://localhost:8080/api/health      # straight to the backend
curl http://localhost:8000/health          # {"status":"UP","embedding_backend":"sentence-transformers"}
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8888/healthz   # SearXNG: 200
docker compose ps
```

The first two must both answer `{"emailConfigured":false,"status":"UP"}`. If only the
second does, the `/api` proxy in `deploy/nginx.conf` is not in place and the page will
report that it cannot reach the backend.

The backend waits for MySQL's healthcheck, so `yci-backend` legitimately starts ~20 s
after `docker compose up` returns. The ML service takes a further ~20 s to load the
embedding model.

Open **http://localhost:8081** and create an account.

### 3.2 Without Docker

You need JDK 17, Maven, Python 3.10+, and a local MySQL 8.

```bash
# 1. database
mysql -u root -p < database/schema.sql
mysql -u root -p -e "CREATE USER IF NOT EXISTS 'yci'@'localhost' IDENTIFIED BY 'yci_password';
                     GRANT ALL ON yci.* TO 'yci'@'localhost'; FLUSH PRIVILEGES;"

# 2. ML service
cd ml-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --port 8000

# 3. backend  (new terminal)
cd backend
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/yci?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
ML_SERVICE_URL=http://localhost:8000 \
mvn spring-boot:run

# 4. frontend  (new terminal)
#    A bare static server does not proxy /api, so point the client at the backend:
#    uncomment window.YCI_API_BASE in frontend/js/config.js first.
cd frontend && python -m http.server 8081

# 5. web search (optional) — the one container worth keeping even in this mode
docker compose up -d searxng      # then WEB_SEARCH_BASE_URL=http://localhost:8888
```

Host names differ between the two modes: in Docker the backend reaches the other services
as `mysql`, `ml-service` and `searxng`; locally it must use `localhost` (and `:8888` for
SearXNG, which is the port compose publishes). `.env` is configured for the **Docker**
path.

### 3.3 Walking the API by hand

```bash
# Through nginx, exactly what the browser does. http://localhost:8080 also works,
# and is the only option when the frontend container is not running.
B=http://localhost:8081

# create an account -> token
T=$(curl -s -X POST $B/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"name":"You","email":"you@example.com","password":"supersecret123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# set the channel and one competitor
curl -s -X PUT  $B/api/channel     -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"channelUrl":"https://youtube.com/@LinusTechTips"}'
curl -s -X POST $B/api/competitors -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"channelUrl":"https://youtube.com/@JayzTwoCents"}'

# keep the first run small
curl -s -X PUT $B/api/settings -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"retrievalMode":"LAST_N_VIDEOS","retrievalValue":3,"ownCommentLimit":150,"competitorCommentLimit":100}'

# use your own keys instead of the server's, then prove they work
curl -s -X PUT $B/api/settings -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"youtubeApiKey":"AIza…","llmBaseUrl":"https://api.openai.com/v1","llmModel":"gpt-4o-mini","llmApiKey":"sk-…"}'
curl -s -X POST $B/api/settings/test/youtube   -H "Authorization: Bearer $T"
curl -s -X POST $B/api/settings/test/llm       -H "Authorization: Bearer $T"
curl -s -X POST $B/api/settings/test/websearch -H "Authorization: Bearer $T"
# "": back to the server's key.  Reading /api/settings never returns a key, only a preview.
curl -s -X PUT $B/api/settings -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"youtubeApiKey":""}'

# generate, then poll
RUN=$(curl -s -X POST $B/api/ideas/generate -H "Authorization: Bearer $T" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["runId"])')
curl -s $B/api/ideas/runs/$RUN -H "Authorization: Bearer $T"
```

Settings updates are all-or-nothing: one invalid field rejects the whole payload with a
message naming it. The bounds, all enforced in `UserSettingsService.update`:

| Field | Accepted |
|---|---|
| `retrievalMode` | `LAST_N_VIDEOS` or `LAST_N_DAYS` |
| `retrievalValue` | 1–50 for `LAST_N_VIDEOS`, 1–365 for `LAST_N_DAYS` — validated against the mode *after* any mode change in the same payload |
| `ownCommentLimit` | 50–5000 |
| `competitorCommentLimit` | 20–2000 |
| `webSearchProvider` | `searxng`, `duckduckgo`, `none`, or `""` to fall back to the server's |
| `llmBaseUrl` / `searxngBaseUrl` | must start with `http://` or `https://`; trailing slashes are trimmed |
| `youtubeApiKey` | at least 20 characters, or `""` to clear |
| `notifyFrequency` | `DAILY` or `WEEKLY`, and `notifyEnabled` requires an email address |

Choosing `searxng` without a URL — yours or the server's — is rejected at save time rather
than silently finding nothing three minutes into a run.

### 3.4 The async job model

`POST /api/ideas/generate` returns `{"runId": N, "status": "QUEUED"}` immediately. Poll
`GET /api/ideas/runs/{id}` until `status` is `COMPLETED` or `FAILED`; the response carries
`progress` (0–100), a human-readable `message`, `errorMessage` on failure, and on success
the `result` and the full `prompt`.

A user can only have one run in flight; a second request is rejected while one is active.

---

## 4. Configuration reference

`docker compose` reads `.env` automatically; `backend/src/main/resources/application.yml`
maps each variable and supplies defaults.

| Variable | Used by | Notes |
|---|---|---|
| `MYSQL_DATABASE` | mysql | **Must match the schema in `MYSQL_URL`** — see §5.1. |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | mysql, backend | Granted rights on `MYSQL_DATABASE` only. |
| `MYSQL_ROOT_PASSWORD` | mysql | Also used by the healthcheck. |
| `MYSQL_URL` | backend | Host is `mysql` in Docker, `localhost` otherwise. |
| `ML_SERVICE_URL` | backend | `http://ml-service:8000` in Docker. |
| `APP_SECRET_KEY` | backend | Encrypts API keys users save in Settings. Blank ⇒ stored as typed, warned about at startup. |
| `YOUTUBE_API_KEY` | backend | **Default** key. Blank is fine if every user brings their own. |
| `YOUTUBE_MAX_COMMENTS_PER_VIDEO` | backend | Hard per-video API cap (default 200). |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | backend | **Default** model. Any OpenAI-compatible endpoint. |
| `LLM_TIMEOUT_SECONDS` | backend | Default 900. Generation is a background job, so a high ceiling is free. |
| `LLM_MAX_PROMPT_CHARS` | backend | Default 24000. The prompt is truncated with a marker beyond this. |
| `WEB_SEARCH_PROVIDER` | backend | `searxng` (default) \| `duckduckgo` \| `none`. |
| `WEB_SEARCH_BASE_URL` | backend | Required for `searxng`; `http://searxng:8080` in Docker. |
| `WEB_SEARCH_FALLBACK` | backend | Try DuckDuckGo when SearXNG returns nothing. Default true. |
| `WEB_SEARCH_MAX_QUERIES` | backend | Searches per run, default 4. Queries come from explicitly requested topics first. |
| `WEB_SEARCH_RESULTS` | backend | Results kept per query, default 4. |
| `WEB_SEARCH_TIMEOUT_SECONDS` | backend | Per-search ceiling, default 15. A failed search is never fatal. |
| `SEARXNG_SECRET` | searxng | Session secret. Any long random string. |
| `SEARXNG_BASE_URL` | searxng | What the instance believes its own address is (its UI links only). |
| `AUTH_TOKEN_TTL_DAYS` | backend | Session length, default 30. |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | backend | Blank host ⇒ digests are saved but never sent. |
| `SMTP_AUTH` / `SMTP_STARTTLS` | backend | Both default true. Set false for a local relay that wants neither. |
| `NOTIFY_FROM` / `NOTIFY_CRON` / `APP_URL` | scheduler | `NOTIFY_CRON` is a 6-field Spring cron, checked hourly by default. |

Three more are set by a compose file rather than `.env`, and are listed here because they
are the ones you reach for when something is wrong:

| Variable | Used by | Notes |
|---|---|---|
| `MODEL_DIR` | ml-service | Where the `.joblib` classifiers are written. `/app/models` in Docker, backed by the `ml_models` volume so a retrain survives a restart. |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | backend | `update` by default. The Azure compose file exposes it as `DDL_AUTO`; `validate` locks the schema down once `schema.sql` has been loaded. |
| `SITE_ADDRESS` | caddy | Server deployments only — the hostname Caddy requests a certificate for. See [`AZURE_DEPLOYMENT.md`](AZURE_DEPLOYMENT.md). |

Per-channel volumes — last N videos vs last N days, comment budgets, web search on/off —
are **per-user settings stored in the database**, not environment variables. They are set
during setup and editable in the settings panel.

`web_search_enabled` defaults to **false**: search results are evidence the creator did not
choose, and a smaller model will happily write about what the web is discussing instead of
what the comment section asked for. The `WEB_SEARCH_*` variables therefore only decide
*which* provider is used by the accounts that opt in.

`.env` is git-ignored and nothing is hardcoded.

### 4.1 Per-user API configuration

Everything in the `YOUTUBE_*`, `LLM_*` and `WEB_SEARCH_*` groups above is only a default.
Each account can override it for itself in **Settings → API access**, stored in the extra
`user_settings` columns:

| Column | Overrides | Empty means |
|---|---|---|
| `youtube_api_key` | `YOUTUBE_API_KEY` | use the server's key |
| `llm_base_url` / `llm_api_key` / `llm_model` | `LLM_*` | use the server's model |
| `web_search_provider` / `searxng_base_url` | `WEB_SEARCH_*` | use the server's provider |

How it fits together:

- `ApiConfigService.forUser(id)` returns an `ApiConfig` — the merged result — and every
  call that needs credentials takes it as an argument. `YouTubeService.session(key)`,
  `LlmClient.complete(cfg, …)` and `WebSearchService.searchAll(cfg, …)` have no ambient
  key of their own, which is what makes it impossible for one user's run to spend
  another's quota.
- The two key columns hold `SecretCipher` output: `enc:<base64 iv+ciphertext>` (AES-GCM,
  fresh IV per value) when `APP_SECRET_KEY` is set, plaintext otherwise. Decryption of a
  value written under a *different* secret fails softly — the key is ignored and the
  server's is used — so a rotated secret degrades instead of breaking.
- Keys are write-only over the API. `SettingsDto` carries `youtubeApiKey` / `llmApiKey`
  inbound only; responses carry `…Set` and a masked `…Preview` instead.
- `""` clears a field back to the server default, `null` leaves it untouched. The frontend
  relies on this: an empty key box means "unchanged", and the *Clear* button is what sends
  the empty string.
- `POST /api/settings/test/{youtube|llm|websearch}` runs the smallest real call each
  provider offers and always answers 200 with `{ok, message, detail}` — a rejected key is
  an answer, not a server error.

An existing database needs the new columns: `ddl-auto: update` adds them on the next boot,
or apply `database/migrations/001_user_api_config.sql` by hand on a managed server.

### 4.2 SearXNG

`deploy/searxng/settings.yml` and `limiter.toml` configure the bundled instance. Two lines
are load-bearing:

- `search.formats` includes `json`. SearXNG ships with HTML only, and the backend's
  `/search?format=json` gets a **403** without it.
- `server.limiter: false`. The limiter is bot protection; a server-side caller with no
  browser headers looks exactly like a bot to it.

Both are safe here because the instance is only reachable inside the compose network.
The directory is mounted read-only — the container takes its secret from `SEARXNG_SECRET`
at startup, so nothing needs to write back into it. Browse it on <http://localhost:8888>
to see exactly what the backend sees.

### Reaching services on the host from a container

`localhost` inside a container is that container. To reach something on your machine (a
local Ollama, LM Studio, vLLM), use `host.docker.internal` **and** the gateway mapping —
`docker-compose.yml` already gives `backend`:

```yaml
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

The host process must also listen on `0.0.0.0`, not `127.0.0.1`. For Ollama:

```bash
sudo mkdir -p /etc/systemd/system/ollama.service.d
printf '[Service]\nEnvironment="OLLAMA_HOST=0.0.0.0"\n' \
  | sudo tee /etc/systemd/system/ollama.service.d/override.conf
sudo systemctl daemon-reload && sudo systemctl restart ollama
```

---

## 5. Troubleshooting

Start here — it names the process to blame:

```bash
docker compose ps                  # who exited?
docker logs yci-backend | head -60
docker logs yci-mysql  | tail -30
docker logs yci-ml-service | tail -20
```

### 5.1 `Access denied for user 'yci'@'%' to database 'yci'`

The backend exits with code 1 and Hibernate reports *"Unable to determine Dialect without
JDBC metadata"* further down — that second message is a symptom, not the cause.

**Cause:** `MYSQL_DATABASE` names a different schema than `MYSQL_URL` connects to. The
MySQL entrypoint grants the application user privileges on `MYSQL_DATABASE` and nothing
else.

**Fix:** make them agree, then recreate the volume — grants and
`/docker-entrypoint-initdb.d` scripts run **only on an empty data directory**:

```bash
docker compose down -v        # destroys the database
docker compose up -d
```

### 5.2 Connection refused to MySQL or the ML service from the backend

`ML_SERVICE_URL` or `MYSQL_URL` points at `localhost`. Inside the backend container that
is the backend itself. Use the compose service names: `mysql`, `ml-service`.

### 5.3 The page breaks with `API.<something> is not a function`

The browser is running a cached copy of an older `js/api.js` whose exports no longer match
`app.js` — `API.setBase` in particular no longer exists, since the backend URL was removed
from the frontend. Hard-refresh (`Ctrl+Shift+R`) once.

`deploy/nginx.conf` now sends `Cache-Control: no-cache` on every asset so the browser
revalidates instead of silently reusing a stale file. If you edit the frontend and see no
change, confirm the header survived:

```bash
curl -sI http://localhost:8081/js/api.js | grep -i cache-control
```

### 5.4 Idea generation fails with `The model did not respond`

Either the endpoint is unreachable or the model exceeded `LLM_TIMEOUT_SECONDS`.

```bash
docker exec yci-backend sh -c 'curl -s -m 5 -o /dev/null -w "%{http_code}\n" \
  http://host.docker.internal:11434/api/tags'
```

`000` means nothing accepted the connection — your LLM server is bound to `127.0.0.1`;
apply the `OLLAMA_HOST=0.0.0.0` override in §4. A timeout instead usually means a
**reasoning model**: see §5.5.

### 5.5 Generation takes many minutes on a local model

Reasoning ("thinking") models spend most of their budget on hidden tokens. Measured on
CPU here: `qwen3.5:4b` needed **75 s to answer "say hi in one word"**, while
`qwen2.5:3b-instruct` answers the full idea prompt in ~30 s.

Ollama's `think: false` only works on its native `/api/chat`; the OpenAI-compatible
`/v1/chat/completions` route this app uses **ignores it**. So pick a non-reasoning model:

```bash
ollama pull qwen2.5:3b-instruct     # then set LLM_MODEL and restart the backend
```

### 5.6 Ideas come back with no web context

First check whether they were meant to: web search is **off by default** per account
(**Settings → Reading**). With it on, the UI says so above the results when a search came
back empty, and **Settings → API access → Test search** will say the same thing with the
reason attached. Check the provider in play too — it may be the user's own choice, not the
server's.

**With SearXNG** (the default), ask it directly:

```bash
docker exec yci-backend sh -c 'curl -s -m 15 \
  "http://searxng:8080/search?q=test&format=json" | head -c 200'
```

- `403` ⇒ the instance does not allow the `json` format. Add it under `search.formats` in
  `deploy/searxng/settings.yml` and restart: `docker compose restart searxng`.
- `429` or an HTML challenge ⇒ the bot limiter is on. `server.limiter: false`.
- Connection refused ⇒ the container is down (`docker logs yci-searxng`) or
  `WEB_SEARCH_BASE_URL` points somewhere else.
- Valid JSON with `"results": []` ⇒ the instance is fine but its upstream engines returned
  nothing; try the query in the UI on <http://localhost:8888>.

**With DuckDuckGo**, throttling is the usual answer — it replies `202` with a challenge
page containing zero results:

```bash
docker exec yci-backend sh -c 'curl -s -m 15 -d "q=test" \
  https://html.duckduckgo.com/html/ -o /tmp/d.html -w "%{http_code}\n"; grep -c result__a /tmp/d.html'
```

`202` and `0` means throttled. That is precisely why SearXNG is the default.

### 5.7 "No YouTube API key is available" / "No language model is configured"

A run is refused before any ingestion when neither the user nor the server has usable
credentials. Open **Settings → API access**: the pill under each field says which key is in
play, and *Test* proves it. A key saved while `APP_SECRET_KEY` was set and then read back
after that variable changed is treated as absent — the backend logs
*"Could not decrypt a stored secret"* and falls back to the server's key.

### 5.8 `/health` on port 8000 returns nothing

Normal for the first ~20 s while `all-MiniLM-L6-v2` loads. If the health response reports
`embedding_backend: tfidf-fallback`, the service still works with lower semantic quality.

### 5.9 Ports already in use

3306 collides with a locally installed MySQL more often than not, and 8888 with another
local tool now and then. Either stop the other process or remap in `docker-compose.yml`,
e.g. `"3307:3306"` / `"8889:8080"` — the backend talks to both over the compose network,
so the published ports only matter to your own browser and client tools.

### 5.10 Starting clean

```bash
docker compose down -v --remove-orphans
docker compose up -d --build
```

---

## 6. Schema notes worth knowing

Two constraints are deliberate and easy to get wrong when editing the schema:

**Video and comment uniqueness is per channel, not global.** Two users may track the same
YouTube channel. With a global unique key on `youtube_video_id`, the second user's ingest
*reassigns* the first user's rows (their comments cascade along) instead of creating its
own copy. The keys are `(channel_id, youtube_video_id)` and
`(video_id, youtube_comment_id)`, and the repositories query by both columns.

**`user_settings` carries secrets.** `youtube_api_key` and `llm_api_key` hold ciphertext
(`enc:` prefix) whenever `APP_SECRET_KEY` is set, so a database dump does not hand over
anyone's API keys — but a dump plus that environment variable does. Treat both as
sensitive, and note that the columns are wide (512) because encryption inflates the value
by roughly half. Existing databases get these columns from `ddl-auto: update`, or from
`database/migrations/001_user_api_config.sql` where the schema is applied by hand.

**Every child table needs an explicit foreign key.** The entities use plain `Long userId`
columns rather than JPA associations, so `ddl-auto=update` creates those tables with **no
foreign keys at all** — deleting a user would orphan its settings, runs and tokens.
`database/schema.sql` declares them; if a table was ever created by Hibernate against an
existing database, add the constraint by hand. `AuthService.resolveUserId` also re-checks
that the account still exists, so a stray token cannot authenticate.

---

## 7. Tests

```bash
cd backend && mvn test                    # prompt assembly, auth, per-user API config, context wiring
cd ml-service && pip install pytest && pytest   # preprocessing, models, topics
```

`pytest` is deliberately absent from both requirements files, so a fresh virtualenv needs
that one extra install. Note also that `demand.py` — the module the whole service exists
for — has no tests; `ml-service/README.md` §8 says what to write first.

`UserApiConfigTest` is the one to read before touching credential handling: it pins that a
user's key overrides the server's, that `""` clears it back, that a saved key is never
serialised out of the API, and that what lands in the database is ciphertext.

Inside Docker, without a local JDK:

```bash
docker run --rm -v "$PWD/backend":/app -v yci_m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-17 mvn test
docker compose exec ml-service pytest
```

The Spring tests boot the context with `SpringApplicationBuilder` rather than
`@SpringBootTest`, because Boot's test listeners initialise Mockito and its agent fails to
load on some JDK/container combinations — an infrastructure detail that should not decide
whether the wiring check can run.

---

## 8. Extending it

- **Swap the LLM provider** — repoint `LLM_BASE_URL` / `LLM_MODEL`, or replace
  `service/ai/LlmClient`.
- **Change what the model is asked** — `PromptBuilder.SYSTEM` holds the instructions and
  the output shape; `PromptBuilder.build()` assembles the evidence. `PromptBuilderTest`
  covers the rules, including the "nothing to work with" guard.
- **Improve demand extraction** — `REQUEST_PATTERNS` in `ml-service/app/demand.py` is the
  first thing to reach for; `stopwords.py` controls what can appear in a label.
- **Add a search provider** — one method in `WebSearchService`, selected by
  `app.web-search.provider`, plus its name in `UserSettingsService.PROVIDERS` and an
  `<option>` in the settings pane so users can choose it too.
- **Add another per-user credential** — a column on `UserSettings`, a merge line in
  `ApiConfigService`, the field on `Dto.SettingsDto` (write-only if it is a secret) and a
  case in `ConnectionTestService`. Nothing else reads credentials directly.
- **Add an ML endpoint** — define it in `app/main.py` with a schema in `app/schemas.py`,
  then add the matching call to `service/ml/MlClient`.
