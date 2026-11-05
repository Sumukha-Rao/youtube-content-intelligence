# Verified competitor discovery and transcript outlines

The existing Spring Boot YouTube/LLM clients, per-user API settings, FastAPI service,
idea-run storage, competitor ingestion and vanilla JavaScript UI are reused. The
existing audience-demand ML/NLP pipeline is unchanged. No new models or services
are required.

## Creator channel and competitors

Saving a creator channel during setup or in Settings automatically loads suggestions.
The backend fetches the channel and 15 recent uploads (titles, descriptions, tags,
language, publication dates and views), then asks the configured LLM for its niche,
language, audience and about ten channels. Generated links are never displayed directly.

YouTube API channel details and bounded name searches resolve candidates, including
suggestions with missing, invalid or incorrect links/IDs. A second LLM check compares
the actual API-returned names/descriptions with the intended identity, niche and audience.
Only supplied, API-verified IDs can pass this check. Results use canonical channel URLs;
invalid channels, uncertain matches, duplicates, the creator and already monitored
channels are excluded. Fewer than ten results are valid. The check is evidence-based
but an LLM assessment can still misjudge relevance.

Verified results are cached on the existing `channels` row, along with the detected
niche/audience/language. Each discovery is bounded to 15 proposed channels and up to
three name-search candidates each. Name searches spend YouTube search quota; caching
avoids repeating them on page loads. API/quota/network failures show an error with a
Retry button, rather than caching a misleading empty success.

Skipping creator setup makes no discovery/LLM requests and hides suggestions. Manual
competitor entry remains available. Adding a creator later enables discovery.

## Recommendations and related videos

Competitor ingestion retains its existing video metadata and now also stores a canonical
video URL and channel-name snapshot. New idea prompts include a bounded catalog of
complete video metadata records using real YouTube video IDs as stable identifiers.
Recent records are included across channels in turn within the prompt budget. Audience
analysis and optional web evidence still feed the same recommendation LLM.

Each idea must return `related_video_ids`. Unknown IDs reject the response; they are
never accepted as sources. Validated idea data and related video snapshots are stored
in `idea_runs.recommendation_data`. This reuses the existing table and preserves the
associations even if a competitor is removed later. Markdown is also stored in the
existing `result` field for email digests and older clients. Historical Markdown-only
runs remain readable; generate a new run to get related videos and summary buttons.

## Transcript summaries

Each idea card offers **Get Transcript Summary** with loading and retryable error states.
Only its saved related videos are sent to Python's `/ml/transcripts` endpoint. Both Python
dependency profiles pin `youtube-transcript-api==1.2.4` and use its documented instance
API: [upstream documentation](https://github.com/jdepoix/youtube-transcript-api/tree/v1.2.4).
The implementation uses `YouTubeTranscriptApi().list()`, `find_transcript()` and
`fetch().to_raw_data()` to support manual and automatic captions.

The creator's stored channel language (YouTube default language, or the LLM-detected
language if no default exists) is preferred, then its base language and English. Without
a creator channel, English is used. Unsupported/missing subtitles and inaccessible
videos are skipped. Network, parser, rate-limit and blocked-request failures are returned
as errors, not evidence that captions do not exist. No proxies, cookies, transcription,
translation service or access-restriction bypass is used.

If no related video has captions and no retrieval error occurred, the response and UI
say exactly **No transcript available**. That absence is not cached, so captions added
later can be retried. If all retrievals fail, the user sees a retryable error. Partial
success produces a summary using only retrieved subtitles and warns about failed sources.

At most ten related videos are allowed per idea. Python limits each caption text to
16,000 characters. Java distributes the configured `LLM_MAX_PROMPT_CHARS` user-message
budget across available sources and checks the serialized JSON size, including escaping.
Long subtitles are excerpts, not full-video coverage. The prompt explicitly treats
subtitles as untrusted reference data, requests an original outline, and prohibits
invented sources/timestamps and long copied passages. Summary JSON and source IDs are
validated before saving. Video titles/descriptions are never used to generate a summary
when there are no subtitles.

Successful summaries include a summary, speaking points, concepts, suggested structure
and linked source videos. They are cached per idea in the existing run JSON. Refreshes
and repeated clicks reuse them. A short database row lock merges cache updates after
external calls so concurrent summaries do not overwrite each other. Failed retrievals
and failed/malformed LLM outputs are not cached.

## Frontend presentation

The current UI uses the existing Bootstrap/vanilla JavaScript components, with a split
sign-in screen, responsive source and verified-suggestion cards, light/dark themes and
numbered recommendation cards. Evidence, angle, outline and differentiation use
expandable details; related links and **Get Transcript Summary** stay visible.

Successful summary panels show speaking points and source links. Loading, exact absence
text and retryable errors remain distinct. The polish adds no frontend build step and
changes no API contract. The DOM tests cover interactions; a live visual browser review
was unavailable in this session.

## API

All endpoints require the existing bearer token and enforce ownership. Channel IDs in
these paths are database IDs. An idea is identified by its run ID and zero-based `id`
from `recommendations.ideas`.

| Method | Path | Result |
| --- | --- | --- |
| POST | `/api/channels/{id}/suggest-competitors` | Generate/cache verified suggestions |
| GET | `/api/channels/{id}/suggestions` | Cached suggestions, or empty body before discovery |
| GET | `/api/ideas/runs/{runId}/ideas/{ideaId}/related-videos` | Persisted video associations |
| POST | `/api/ideas/runs/{runId}/ideas/{ideaId}/transcript-summary` | Retrieve captions and generate/cache summary |
| GET | `/api/ideas/runs/{runId}/ideas/{ideaId}/transcript-summary` | Cached summary, or empty body before success |

The existing run/latest/home responses now include `recommendations` alongside the
original `result`. Its `ideas` contain related videos and any cached transcript summary.

## Database and deployment

Fresh databases use the updated `database/schema.sql`. Existing installations with the
default Hibernate `ddl-auto=update` get the nullable columns automatically. If automatic
schema updates are disabled, apply `database/migrations/002_competitor_suggestions_and_summaries.sql`
once before restarting the backend. Do not apply it again after Hibernate has already
added those columns. No data backfill or new tables are required.

Rebuild/restart backend, Python service and frontend together so the new Python dependency,
endpoints and UI are present. Use the existing Docker Compose deployment commands. For
non-Docker Python installs, reinstall the chosen requirements file. Existing YouTube,
LLM and ML service settings/keys are reused; there are no new credentials. Discovery and
summaries run in user-initiated HTTP requests; nginx's read timeout is extended for slow
configured LLMs. Browsers show loading indicators while these requests run.

## Verification

Backend workflow tests use real HTTP clients and H2 persistence with local fake external
providers; no live keys or YouTube access are needed. They cover discovery, skip/manual
entry, invalid links/IDs, adding suggestions, ingestion, persisted associations, ownership,
subtitle success/partial success/absence/temporary failures, source validation, LLM errors
and caching. Prompt tests check complete source records and input budgets.

```bash
cd backend
mvn test                 # Java 17, matching backend/Dockerfile
```

Python tests cover manual/automatic selection through the library's selector, language
fallback, unavailable captions, blocked/network/parser errors, limits and endpoint validation.
The full existing NLP tests can run with the lightweight dependency profile.

```bash
cd ml-service
python -m pip install -r requirements-lite.txt pytest httpx
python -m pytest -q
```

DOM tests load the actual HTML/scripts and exercise suggestion display/addition, skip,
adding a creator later, related links, loading, success, absence and retryable errors.
`jsdom` is a test-only dependency; the shipped frontend remains dependency-free.

```bash
npm install --prefix /tmp/yci-ui-tests jsdom
NODE_PATH=/tmp/yci-ui-tests/node_modules node --test frontend/tests/workflow.test.cjs
node --check frontend/js/app.js
```

Live provider accuracy, current YouTube quota and real-world subtitle accessibility depend
on the configured services and are not established by the deterministic tests.

## Related setup guides

- [Project architecture and local operation](../PROJECT_GUIDE.md)
- [Windows/Linux startup](windows-local.md)
- [Email implementation and SMTP setup](email-notifications-implementation.md)
- [Azure Terraform authentication](../AZURE_TERRAFORM.md#21-authentication-and-github-tokens)

These features reuse the configured YouTube/LLM credentials. A GitHub PAT is not a
runtime requirement and is not needed to clone this public repository for deployment.
