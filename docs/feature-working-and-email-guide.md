# Competitor Discovery and Transcript Summaries

Implementation and email workflow

Prepared 6 November 2026. Source revision: 9ebc626.

YouTube Content Intelligence now helps a creator find competing channels and turn related competitor videos into a speaking outline. This document explains how both changes work, how their data is saved, and how the existing email digest generates and delivers fresh ideas.

The implementation reuses the YouTube Data API, configured LLM, Spring Boot backend, Python service, MySQL tables and frontend. The existing audience-demand ML/NLP pipeline remains in place. No additional model, vector database or ranking service is required.

### What the creator can do

| Capability | Trigger | Result |
| --- | --- | --- |
| Competitor suggestions | Save own channel | Verified channels with a category, reason and Add Competitor action. |
| Transcript speaking outline | Click Get Transcript Summary | An original outline using accessible subtitles from videos already related to the idea. |
| Email digest | Enable Notifications | A scheduled run of fresh ideas delivered through the configured SMTP server. |

### How the workflows connect

Own channel → recent uploads → LLM proposals → YouTube verification

Added competitors → fetched videos → ideas with saved related video IDs

Selected idea → available captions → LLM outline with source references

Email follows a separate scheduled path: a due account receives a fresh recommendation run, and the completed Markdown result is handed to SMTP. Clicking the transcript button is an on-demand action in the app; the email scheduler does not perform it.

### Reading this guide

Pages 2–4 explain the two changes, including the related-video step that makes transcript summaries possible. Pages 5–6 explain email processing and setup. Page 7 maps the endpoints, stored data and implementation files.

## Automatic Competitor Channel Suggestions

Saving the creator’s channel ID or URL during setup or in Settings enables automatic discovery. The creator does not need to enter a niche, language or audience manually. The frontend first checks for saved suggestions and starts discovery only when no cached result exists.

### Working sequence

1. The existing YouTube service resolves the channel input and fetches channel details. Discovery requests up to 15 recent uploads and collects titles, descriptions, available tags, language, views and publication dates.

2. The backend fits complete evidence records into the configured prompt budget. The existing LLM identifies the niche, spoken language and target audience and proposes approximately ten channels in structured JSON.

3. JSON validation checks required fields and types. Each proposal contains a name, category and reason; unknown channel IDs or URLs can be null. Metadata is treated as reference data, not instructions.

4. The YouTube API resolves candidate IDs or supported URLs and also searches by the intended channel name. This catches invented links, incorrect IDs and real channels with similar names.

5. A further LLM check compares actual API-returned candidate names and descriptions with the intended identity, niche, audience and language. It may select only IDs supplied by the backend.

6. The backend excludes duplicate channel IDs, the creator’s own channel and channels already monitored. The prompts also ask for one creator or brand identity per result and reject weak or mismatched-language editions.

7. Only verified candidates are saved and displayed, using canonical YouTube channel URLs. The creator clicks Add Competitor to reuse the existing competitor-add workflow. Discovery does not silently add channels.

### Caching and skip behavior

Detected niche, audience, language and verified suggestions are saved on the existing channels row. Cached reads filter out channels added since discovery. If fewer than ten candidates pass, only those results appear. API or model failures show an error and Retry action rather than a false empty success.

Skipping the own-channel step makes no automatic discovery or LLM calls and hides suggestions. Manual competitor entry remains available. Adding the creator channel later enables discovery. YouTube search consumes quota, and LLM judgments about language or relevance can still be imperfect.

## Related Videos During Idea Generation

The second change begins before captions are requested. Each generated idea stores the competitor videos that informed it, so the app can later retrieve the same sources without asking the LLM to identify them again.

### Collect the recommendation evidence

1. The existing ingestion workflow reads monitored competitors using the account’s video/date limits and comment budgets. It stores the YouTube video ID, title, canonical URL, description, channel-name snapshot, publication date and available view, like and comment counts.

2. The run uses the records actually fetched in that ingestion, rather than all older rows still in the database. Audience comments pass through the existing demand-analysis pipeline. Optional web context is included only when enabled.

3. PromptBuilder assembles a bounded catalog of complete competitor video records. It includes recent records across channels in turn within the input budget. The real YouTube video ID is the stable source identifier.

4. The configured LLM generates ideas with title, description, reason, evidence, angle, outline and differentiation. Every idea must include related_video_ids containing only IDs from the supplied catalog. An empty array is allowed when no supplied video is relevant.

5. RecommendationService validates the structured response. Any invented or unknown video ID rejects the output; duplicate related IDs are removed. The backend assigns each idea a zero-based ID within its run.

6. Validated ideas, related IDs and video metadata snapshots are saved in idea_runs.recommendation_data. A Markdown rendering is also saved in the existing result field for older clients and email delivery.

### What appears on the page

Each idea card shows its title, explanation and related competitor video links. Expandable details contain supporting evidence, angle, outline and differentiation. The Get Transcript Summary button acts on that idea’s saved relationships.

### Why the relationships stay available

Associations are snapshots inside the existing idea run. Removing a monitored competitor later does not remove those historical references, although the source video or its captions may become inaccessible on YouTube. Older Markdown-only runs remain readable; generate a new run to obtain structured associations and transcript actions.

## Transcript Based Speaking Outlines

The creator clicks Get Transcript Summary on a generated idea. The page shows a loading state while the backend checks the saved summary cache and, if needed, retrieves captions from that idea’s saved related videos.

### Working sequence

1. RecommendationService checks account ownership and locates the run and idea. A successful cached summary is returned immediately. With no related videos, it returns No transcript available without a caption or LLM request.

2. The backend sends up to ten saved YouTube video IDs to Python’s /ml/transcripts endpoint. The pinned youtube-transcript-api version 1.2.4 lists tracks and fetches existing manually uploaded or automatically generated captions.

3. Track selection prefers the stored creator-channel language, its base or regional variants, then English. English is used when no creator language is available. The service does not translate captions or transcribe audio.

4. Available caption segments are combined into text, capped at 16,000 characters per video in Python. The backend shares LLM_MAX_PROMPT_CHARS across the available excerpts and checks serialized JSON size so long captions fit the request budget.

5. The same configured LLM receives the selected idea and actual caption excerpts. Instructions require an original outline, treat captions as untrusted reference text, and prohibit invented sources, timestamps or long copied passages.

6. The backend validates the summary fields and every source_video_ids entry against sources whose captions were supplied. It saves the successful result with the idea and displays a summary, what to speak about, important concepts, suggested structure and source-video links.

### Missing captions and failures

| Outcome | Application behavior |
| --- | --- |
| Some captions unavailable | Skip those videos and summarize the available caption text. |
| No suitable captions and no retrieval errors | Display exactly No transcript available. Absence is not cached. |
| All missing with any temporary or blocked failure | Show a retryable retrieval error, not an absence message. |
| Captions available but some retrievals fail | Use available captions and include a warning about failed sources. |
| LLM or JSON validation fails | Show an error; do not cache a failed or malformed summary. |

Titles and descriptions never replace missing subtitles. Long-caption summaries cover excerpts, not necessarily the entire source video. YouTube access restrictions are respected; the implementation adds no proxies, cookies or bypass mechanism.

## How Email Notifications Work

Email is an opt-in digest built around the existing idea-generation workflow. It does not depend on requesting transcript summaries. Users select a recipient and DAILY or WEEKLY frequency under Settings → Notifications.

### Scheduled delivery sequence

1. DigestScheduler runs according to NOTIFY_CRON. Its default six-field Spring cron, 0 0 * * * *, checks at the start of every hour using the server JVM time zone.

2. The scheduler selects accounts with notify_enabled set to true. An account with no last_notified_at is due on the next check. Otherwise, it becomes due after the recorded time plus one day or one week has passed.

3. For a due account, the scheduler creates an idea run and invokes the same YouTube ingestion, audience analysis and configured LLM generation used by manual requests.

4. Only a COMPLETED run is passed to NotificationService. The service checks the recipient, configured SMTP host and available JavaMailSender, then builds a SimpleMailMessage.

5. The subject is Your YouTube video ideas. The plain-text body contains the stored Markdown ideas, outlines, model information, optional web-search note and APP_URL. Markdown markers may remain visible. Later on-demand transcript summaries are not included.

6. If the SMTP handoff succeeds, the run is marked notified. The scheduler records last_notified_at after the completed-run delivery path even if sending was skipped or failed. SMTP acceptance does not guarantee inbox delivery.

### What the current failure paths mean

| Condition | Current behavior |
| --- | --- |
| Generation fails or is incomplete | No digest is sent. This branch continues before updating the timestamp and may retry at a later tick. |
| SMTP missing or sending fails | The completed run is not marked notified, but the attempt timestamp is updated. |
| Exception caught around setup or delivery | The catch block records an attempt timestamp. |

DAILY and WEEKLY are rolling intervals, not chosen clock times. The backend must remain running. There is no send-now endpoint or independent email retry queue; multiple backend replicas require coordination. Blank SMTP_HOST prevents sending but can still allow generation for opted-in accounts, so leave notifications off until SMTP is ready.

## Enable and Check Email Delivery

SMTP means the mail server that accepts the digest for delivery. Configure its credentials on the backend separately from each account’s recipient and notification preference. Use your provider’s approved SMTP settings and an address you control for the first test.

### Configure the backend

Edit the existing private .env file and replace these illustrative values with your provider’s details. Port 587 and authentication with STARTTLS are the application defaults; confirm that your provider supports them. Keep passwords out of source control and chat.

```text
SMTP_HOST=smtp.yourprovider.example
SMTP_PORT=587
SMTP_USERNAME=your-smtp-user
SMTP_PASSWORD=YOUR_PROVIDER_CREDENTIAL
NOTIFY_FROM=sender@yourdomain.example
NOTIFY_CRON=0 0 * * * *
APP_URL=http://localhost:8081
```

Use a permitted sender for NOTIFY_FROM. For deployment, APP_URL must be the public application URL; localhost links only work on the machine running the app. Recreate the backend after changing environment values. For the local stack, run from the repository root:

```text
docker compose up -d --no-deps --force-recreate backend
```

For Azure, use the deployment Compose file and environment options documented in AZURE_TERRAFORM.md instead. application.yml reads SMTP_AUTH and SMTP_STARTTLS, but the Compose files need explicit forwarding before optional overrides in .env take effect.

### Enable and verify an account

1. Sign in, configure working YouTube and LLM access, and add an own channel or at least one monitored competitor so generation has evidence.

2. Open Settings → Notifications, enable automatic ideas, choose Every day or Every week, enter the test inbox and save.

3. Check /api/health for emailConfigured: true. This verifies host/sender configuration only; it does not test credentials, network access or final delivery.

4. Wait for a due scheduler check. Inspect backend logs for a completed run and Digest emailed to, then check inbox and spam. If sending fails, inspect SMTP credentials, provider policy, permitted sender, TLS and network access.

For a controlled local first-delivery test, NOTIFY_CRON=0 * * * * * checks every minute after backend recreation. It does not override the daily/weekly due interval. Restore the hourly cron afterward; scheduled generation uses normal YouTube quota and LLM requests. Do not reset another user’s timestamps to force delivery.

## Implementation Reference

The current source was inspected on 6 November 2026 at repository revision 9ebc626. All feature endpoints use the existing bearer-token authentication and enforce account ownership. Channel path IDs are database IDs; an idea uses its run ID and its zero-based id in recommendations.ideas.

### Feature endpoints

```text
GET  /api/channels/{id}/suggestions
POST /api/channels/{id}/suggest-competitors
GET  /api/ideas/runs/{runId}/ideas/{ideaId}/related-videos
GET  /api/ideas/runs/{runId}/ideas/{ideaId}/transcript-summary
POST /api/ideas/runs/{runId}/ideas/{ideaId}/transcript-summary
```

Idea generation retains POST /api/ideas/generate and run polling. Notification preferences use the existing settings API. Caption retrieval is internal POST /ml/transcripts; it is not a separate public frontend service.

### Persistence in existing tables

| Table | Saved data |
| --- | --- |
| channels | content_language and competitor_suggestions JSON, including niche and audience. |
| competitor_videos | Video metadata plus video_url and channel_name snapshots. |
| idea_runs | recommendation_data JSON with related sources and cached summaries; result Markdown and notified flag. |
| user_settings | notify_enabled, notify_frequency, notify_email and last_notified_at. |

No new tables are required. Fresh installations use database/schema.sql. Existing installations with schema updates disabled apply migration 002 only when its columns are missing. Do not rerun it after Hibernate or an earlier migration has added them.

### Source files and further reading

Under backend/src/main/java/com/yci: service/CompetitorDiscoveryService.java handles discovery; service/IdeaService.java and service/PromptBuilder.java assemble recommendations; service/RecommendationService.java validates sources and summaries; scheduler/DigestScheduler.java and service/NotificationService.java generate and send digests.

ml-service/app/transcripts.py implements caption retrieval. frontend/js/app.js renders suggestions, idea cards and summaries. SMTP bindings live in backend/src/main/resources/application.yml and the Compose files. Feature details and test commands are in docs/recommendation-features.md; provider setup and troubleshooting are in docs/email-notifications-implementation.md.

The 1 November review recorded 47 backend, 35 Python and 8 frontend DOM tests passing. Those are historical verification results, not a new live-provider or email-delivery test performed for this document.
