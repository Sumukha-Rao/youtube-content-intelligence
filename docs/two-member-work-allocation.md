# YouTube Content Intelligence — Two-Member Work Allocation

## Purpose

YouTube Content Intelligence helps creators decide what video to make next using their audience’s comments, competitor videos and optional web context. This document proposes a division of responsibility for the existing project, with a brief explanation of each feature’s implementation. It is an ownership plan, not a record of past individual contributions.

Replace **Member 1** and **Member 2** with the team members’ names. Infrastructure provisioning is outside this allocation.

## Responsibility overview

| Member | Main responsibility | Expected contribution |
| --- | --- | --- |
| **Member 1** | Application, user accounts and data collection | Account security, channel setup, YouTube ingestion, database foundations, settings, notifications and the corresponding screens. |
| **Member 2** | Audience intelligence and recommendations | Python analysis, competitor discovery, prompt construction, AI generation, related videos, transcript outlines and the corresponding screens. |
| **Both** | Integration and delivery | Agree on API contracts, review each other’s changes, verify the complete workflow and prepare the project demonstration. |

## Member 1 — Application and data management

| Feature / work item | Brief implementation | Main code locations |
| --- | --- | --- |
| **Registration, login and logout** | Spring Boot authenticates email/password accounts using BCrypt password hashes. Database-backed bearer tokens identify users, expire and can be revoked on logout. Protected requests resolve the current user and services check resource ownership. | `AuthService.java`, `AuthController.java`, `security/`, `frontend/index.html` |
| **Creator channel setup** | Accept a channel URL, handle or ID, resolve it through the YouTube client and save the creator’s channel metadata. Support replacing/removing the channel and skipping setup. | `ChannelService.java`, `ChannelController.java`, `service/youtube/YouTubeService.java` |
| **Manual competitor management** | Add, list and remove monitored competitors through REST endpoints. Validate channels using YouTube data and persist each user’s competitor records. Member 2 supplies automatic suggestions. | `CompetitorService.java`, `CompetitorController.java` |
| **Video and comment ingestion** | Fetch creator and competitor uploads and relevance-ordered comments through YouTube Data API v3. Read either the last N videos or videos from the last N days. Spread comment budgets across videos and update existing records using YouTube identifiers. | `IngestService.java`, `service/youtube/YouTubeService.java` |
| **Database and persistence** | Maintain the MySQL schema, JPA entities and repositories for users, tokens, settings, channels, videos, comments and idea runs. Coordinate recommendation JSON and suggestion-cache fields with Member 2. | `database/schema.sql`, `database/migrations/`, `entity/`, `repository/` |
| **User preferences and API access** | Save retrieval limits, search preferences and notification settings. Resolve per-user API overrides against server defaults. Return masked key previews; encrypt saved keys with AES-GCM when `APP_SECRET_KEY` is configured. Provide connection-test actions. | `UserSettingsService.java`, `ConnectionTestService.java`, `service/credentials/`, `SettingsController.java` |
| **Email digests** | A scheduled task checks opted-in accounts for daily/weekly intervals, calls the existing idea-generation pipeline and sends the saved Markdown result as plain text through SMTP. Member 2 owns the generation logic reused here. | `scheduler/DigestScheduler.java`, `NotificationService.java` |
| **Application screens and local runtime** | Own sign-in, setup, channel/competitor management and settings UI. Maintain Docker Compose, static serving and the `/api` reverse proxy for running the application together. | `frontend/index.html`, `frontend/app.html`, `frontend/js/api.js`, `docker-compose.yml`, `deploy/nginx.conf` |

**Member 1 deliverable:** A user can register, configure credentials, save sources, collect data, change settings and opt into configured email digests.

## Member 2 — Intelligence and recommendation generation

| Feature / work item | Brief implementation | Main code locations |
| --- | --- | --- |
| **Audience-demand extraction** | FastAPI receives comment text and like counts. Weighted request patterns extract subjects such as “make a video on X”; likes increase their weight. Embeddings and clustering group similar requests, while stopwords and topic normalization improve labels. Frequent mentions provide a weaker fallback signal. | `ml-service/app/demand.py`, `stopwords.py`, `embeddings.py`, `main.py` |
| **Additional ML utilities** | Maintain standalone sentiment, intent, topic, request-clustering, similarity and training endpoints. These support separate analysis; they are not all invoked by the main recommendation pipeline. | `ml-service/app/models.py`, `preprocessing.py`, `topics.py`, `train.py`, `data/` |
| **Automatic competitor discovery** | Analyze creator metadata and recent uploads with the LLM to propose similar channels. Resolve candidates through the YouTube API, verify their identity/relevance, exclude duplicates and already monitored channels, then cache suggestions. | `CompetitorDiscoveryService.java`, `CompetitorSuggestionController.java` |
| **Optional web context** | Build search queries from audience-demand topics. Retrieve context through SearXNG, with optional DuckDuckGo fallback. Search is opt-in per account; failed searches allow generation to continue using channel evidence. | `service/websearch/WebSearchService.java`, `PromptBuilder.java`, `deploy/searxng/` |
| **Prompt construction and AI ideas** | Combine creator demand, competitor demand/video metadata and optional web evidence within a prompt budget. Call an OpenAI-compatible LLM and validate structured ideas containing a title, description, reason, evidence, angle, differentiation and outline. Save both JSON and Markdown. | `PromptBuilder.java`, `service/ai/LlmClient.java`, `IdeaService.java`, `RecommendationService.java` |
| **Background generation and history** | Queue generation asynchronously and persist `QUEUED`, `RUNNING`, `COMPLETED` or `FAILED` states with progress. Expose polling, latest-result and history endpoints; retain the exact prompt for inspection. | `IdeaService.java`, `IdeaController.java`, `RunReconciler.java` |
| **Related competitor videos** | Supply real video IDs in the prompt, reject unknown IDs in model output and save validated video snapshots with each idea. Associations remain available if a monitored competitor is later removed. | `RecommendationService.java`, `RecommendationController.java`, `IdeaRun.java` |
| **Transcript-based speaking outlines** | Python retrieves accessible captions for an idea’s saved related videos. Java asks the LLM for an original summary, speaking points, concepts and structure using those captions. Validate source IDs and cache successful summaries per idea. | `ml-service/app/transcripts.py`, `service/ml/MlClient.java`, `RecommendationService.java` |
| **Recommendation workspace** | Own suggestion cards, numbered idea cards, progress polling, full-prompt display, related links and on-demand transcript panels with loading, success, absence and retry states. Coordinate shared HTML, JavaScript and styles with Member 1. | `frontend/js/app.js`, `frontend/app.html`, `frontend/css/styles.css` |

**Member 2 deliverable:** Collected evidence produces saved video ideas with traceable related videos, visible generation progress and optional caption-based speaking outlines.

## Integration and shared verification

| Integration point | Member 1 provides | Member 2 provides |
| --- | --- | --- |
| Data → analysis | Persisted videos, comment text, likes and retrieval settings | `/ml/demand` results and Java ML client integration |
| Sources → discovery | Saved creator channel and competitor add/remove APIs | Verified suggestions and suggestion UI |
| Settings → generation | Effective per-user credentials and preferences | LLM/search calls that use those settings |
| Generation → display/email | Shared application shell and digest delivery | Run status, recommendation JSON, Markdown and prompt |
| Captions → outline | Account ownership and shared persistence conventions | Caption retrieval, summary validation, caching and display |

Both members should review changes to `frontend/js/app.js`, `frontend/app.html`, shared DTOs and the database schema before merging, because both work areas use these files.

Use the existing backend tests (`backend/src/test`), Python tests (`ml-service/tests`) and frontend workflow tests (`frontend/tests/workflow.test.cjs`) for the affected features. Together, demonstrate signup → source setup → ingestion → idea generation → related videos → transcript outline. Verify ownership checks and missing-credential/error states, and test email delivery when SMTP is configured.

## Implementation boundaries to explain during review

- Demand analysis is internal evidence for the LLM; the creator sees recommendations rather than raw comment-mining output. Both creator and competitor comments can contribute demand signals.
- Embeddings use sentence-transformers when available, with TF-IDF fallback in the lightweight profile.
- Transcript outlines require accessible captions. Missing captions show **No transcript available**; temporary retrieval failures remain retryable.
- Key encryption depends on `APP_SECRET_KEY`. Live YouTube, LLM, search and email behavior depends on configured services and provider availability.
- Email scheduling uses rolling daily/weekly intervals. **Send mail** can also email an existing completed run through `POST /api/ideas/runs/{id}/email`; there is no independent delivery retry queue.

## Suggested execution order

1. **Foundation:** Member 1 handles accounts, persistence and source setup; Member 2 reviews the ML interfaces and recommendation data contract.
2. **Core pipeline:** Member 1 handles ingestion/settings; Member 2 handles demand analysis, prompts and saved recommendations.
3. **Supporting features:** Member 1 handles notifications/runtime; Member 2 handles discovery, related videos and transcript outlines.
4. **Joint completion:** Integrate screens, run relevant checks, review failure cases and prepare a demonstration of each member’s assigned features.
