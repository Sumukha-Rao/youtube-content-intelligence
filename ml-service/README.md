# ML Service — audience demand extraction

The Python half of [YouTube Content Intelligence](../README.md). A FastAPI service that
reads a channel's comments and answers one question: **what is this audience asking for?**

Its output is never shown to anyone. It goes into the prompt the backend hands to the LLM,
which is what turns it into readable video ideas. So the goal here is not a pretty topic
list — it is evidence a language model cannot misread.

Companion docs: [`../README.md`](../README.md) (what the platform does),
[`../PROJECT_GUIDE.md`](../PROJECT_GUIDE.md) (running the whole stack, troubleshooting).

---

## 1. What the backend actually calls

Only two of the eight endpoints below are on the idea path:

| Endpoint | Called by |
|---|---|
| `POST /ml/demand` | `MlClient.demand(...)` — once per channel per run, 180 s timeout |
| `GET /health` | `MlClient.healthy()` — 5 s timeout |

The rest are standalone analysis endpoints. They work, they are tested, and nothing in
the Spring Boot backend invokes them — useful for inspecting a comment set by hand, and
the obvious starting point if you want to put more ML on the idea path.

## 2. `POST /ml/demand` — the one that matters

```jsonc
// request
{
  "comments":    ["Please make a video on Kafka consumer groups", "..."],
  "like_counts": [42, 0],        // optional, positional; [] or omitted is fine
  "max_topics":  12              // default 12
}
```

```jsonc
// response
{
  "demand_topics": [
    {
      "label":         "Kafka Consumer Groups",
      "demand_score":  8.7412,   // sum of request weights in the cluster
      "request_count": 3,        // explicit asks; 0 for a "mentions" topic
      "mention_count": 3,
      "examples":      ["Please make a video on Kafka consumer groups"],  // <=3 source comments, truncated to 400 chars
      "source":        "requests"   // "requests" | "mentions"
    }
  ],
  "mentioned_terms":   [{ "term": "Cable Management", "mentions": 7, "score": 2.91 }],
  "comments_analyzed": 812,
  "requests_found":    37,
  "backend":           "sentence-transformers"   // or "tfidf-fallback" / "empty"
}
```

`demand_score` is a raw sum, not a normalised 0–1 value: compare topics within one
response, never across two.

### How a comment becomes a topic

The pipeline is **rule-first, not clustering-first**, because an explicit ask is by far the
strongest signal a comment section produces. Everything below is in
[`app/demand.py`](app/demand.py).

1. **Match request patterns.** `REQUEST_PATTERNS` is a weighted list of regexes — "make a
   video on X" and "can you cover X" score 3.0, "more videos on X" 2.3, "waiting for X"
   lower. Each captures the *subject* X, not the boilerplate around it.

   One subtlety worth preserving if you edit them: the optional article prefix is
   `(?:(?:a|an|the)\s+)?` and **must** consume its trailing space. Written as `(?:a|an|the)?\s*`
   it happily eats the leading "A" of "AWS Lambda" and captures `WS Lambda`.

2. **Keep one interpretation per comment.** Only the highest-weight match survives, so a
   single verbose comment cannot stuff the ranking with near-duplicate subjects.

3. **Tidy the subject.** `_tidy_subject()` strips leading filler ("a video on kafka" →
   "kafka") and trailing filler ("kafka please bro" → "kafka"), then rejects what is left
   if it is under 3 characters, over 14 words, pure filler, or a bare pronoun.

4. **Weight by agreement.** `+0.6 × log10(1 + likes)` — 10 likes ≈ +0.6, 100 ≈ +1.2.
   Deliberately gentle: a popular ask should outrank an unpopular one, not bury it.

5. **Cluster paraphrases.** Subjects are embedded and grouped with agglomerative
   clustering at roughly one cluster per two requests, so "kafka consumer groups" and
   "consumer group rebalancing" collapse into one topic. Under 4 requests, no clustering.

6. **Label from content words only.** `_best_label()` prefers the shortest phrasing that
   several cluster members reduce to, and otherwise takes the member closest to the
   centroid — both filtered through [`app/stopwords.py`](app/stopwords.py). `_title()`
   then title-cases it without mangling acronyms (`aws` → `AWS`, `k8s` → `K8S`) or
   version numbers. This is the fix for a real bug: an earlier version produced topics
   literally named *"To / The"*, because the labelling path had no stopword list at all.

7. **Fold in what people merely mention.** `_mentioned_terms()` pulls frequent
   content-bearing n-grams from *all* comments as a weak secondary signal, scored at half
   weight and tagged `source: "mentions"`. A term already covered by a request topic is
   dropped.

### Two rules that keep labels usable

- **Multi-word only for `mentions`.** A bare unigram is almost never a video topic, and it
  poisons the web search the backend builds from these labels — "Linus" returns *Linux*
  articles, "Price" returns cost-of-living listicles. "cable management" is real.
- **Singular/plural and word-order collapse** via `topic_key()`, so "Cable" and "Cables",
  or "docker network" and "network dockers", cannot each take a slot.

### Degenerate cases

| Input | Result |
|---|---|
| No comments | all-empty response, `backend: "empty"` |
| Comments, but no pattern matches | `mentioned_terms` promoted to `demand_topics`, `requests_found: 0` |
| Fewer than 8 usable comments | `mentioned_terms` is empty — TF-IDF `min_df=3` has nothing to work with |

That middle row is the honest outcome for a channel whose audience never asks for
anything: you get mentions, not requests, and the prompt says as much.

## 3. The other endpoints

| Endpoint | Body | Returns |
|---|---|---|
| `GET /health` | — | `{"status": "UP", "embedding_backend": "..."}` |
| `POST /ml/sentiment` | `{"comments": [...]}` | per-comment `POSITIVE`/`NEGATIVE`/`NEUTRAL` + score |
| `POST /ml/intent` | `{"comments": [...]}` | per-comment intent + score, 7 classes |
| `POST /ml/topics` | `{"texts": [...], "min_topic_size": 5}` | topics (label, keywords, size, representative docs) + per-text assignments |
| `POST /ml/cluster-requests` | `{"texts": [...]}` | clusters of paraphrased requests |
| `POST /ml/similarity` | `{"query": "...", "corpus": [...]}` | `max_similarity`, `mean_similarity` |
| `POST /ml/train` | — | retrains sentiment + intent from the seed CSVs, writes to `MODEL_DIR` |

Intent classes: `CONTENT_REQUEST`, `QUESTION`, `PRAISE`, `CRITICISM`, `SUGGESTION`,
`COMPLAINT`, `OTHER`.

`/ml/train` rewrites the model files in place and is unauthenticated, like everything
here — see §7.

## 4. Code map

| File | Role |
|---|---|
| `app/main.py` | FastAPI app. Warms the classifiers on startup so the first request is not the slow one. |
| `app/demand.py` | **The core.** Request patterns, subject extraction, clustering, labelling. |
| `app/stopwords.py` | Vocabulary banned from labels, plus `topic_key()` for plural/word-order dedupe. |
| `app/embeddings.py` | `embed()` and `backend_name()`. sentence-transformers when importable, TF-IDF otherwise. |
| `app/preprocessing.py` | Unicode normalisation, URL/mention stripping, spam and duplicate filtering, language detection. |
| `app/topics.py` | Generic topic discovery, request clustering, similarity. Not on the idea path. |
| `app/models.py` | Sentiment (TF-IDF → Logistic Regression) and intent (TF-IDF → calibrated LinearSVC). |
| `app/train.py` | `python -m app.train` — the same training as `POST /ml/train`, from a shell. |
| `app/schemas.py` | Every request and response model. The API contract in one file. |
| `data/*.csv` | Seed training data: 52 intent rows, 45 sentiment rows. |
| `models/*.joblib` | Trained artefacts. Written to `MODEL_DIR`, trained on demand if missing. |

Two stopword lists exist on purpose and are not interchangeable.
`preprocessing.STOPWORDS` is conservative and *optional* (off by default) because
stripping filler from a short comment can destroy it. `stopwords.LABEL_STOPWORDS` is
aggressive and mandatory, because a label built out of filler is worthless.

## 5. Running it

Inside the full stack, `docker compose up` starts it on **:8000**; see
[`../PROJECT_GUIDE.md`](../PROJECT_GUIDE.md) §3. On its own:

```bash
cd ml-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt          # or requirements-lite.txt, see §6
uvicorn app.main:app --reload --port 8000
```

```bash
curl localhost:8000/health
# {"status":"UP","embedding_backend":"sentence-transformers"}

curl -s localhost:8000/ml/demand -H 'Content-Type: application/json' -d '{
  "comments": ["Please make a video on Kafka consumer groups",
               "Can you cover consumer group rebalancing",
               "more videos on docker networking please"],
  "like_counts": [40, 3, 0]
}' | python3 -m json.tool
```

Interactive docs are at <http://localhost:8000/docs> — FastAPI generates them from
`app/schemas.py`, so they are always current.

The first start with the full requirements takes ~20 s while `all-MiniLM-L6-v2` downloads
and loads; `/health` does not answer until it has. Set `MODEL_DIR` to control where the
`.joblib` files are written (the container sets `/app/models`, backed by a volume, so
retrained models survive a restart).

## 6. Two dependency sets

Everything heavy is imported lazily, and both `embeddings.py` and `topics.py` fall back to
TF-IDF when the import fails. Dropping the semantic stack is therefore a supported
configuration, not a hack.

| | `requirements.txt` | `requirements-lite.txt` |
|---|---|---|
| Extra packages | sentence-transformers, bertopic, hdbscan, nltk (and `torch`) | — |
| Image | 10.4 GB | **711 MB** |
| Memory in use | 1.26 GB | **108 MB** |
| `/health` reports | `sentence-transformers` | `tfidf-fallback` |
| Request-pattern matching | identical (pure regex) | identical |
| Clustering of paraphrases | semantic embeddings | TF-IDF vectors |

What survives intact is the part that matters most: explicit asks are found by regex,
which needs no model at all, and labels go through the same stopword list either way.
What degrades is the *grouping* — TF-IDF clusters by shared vocabulary rather than
meaning, so "kafka consumer groups" and "consumer group rebalancing" may stay separate.

`Dockerfile` builds the full image, `Dockerfile.lite` the small one. The lite build exists
for 1 GiB hosts; see [`../AZURE_DEPLOYMENT.md`](../AZURE_DEPLOYMENT.md) §7.

## 7. Notes

- **No authentication.** The service trusts every caller. In `docker-compose.yml` it
  publishes :8000 for local curl-ing; in the Azure compose file it only `expose`s the port
  to the internal network. Do not put it on a public interface.
- **Stateless per request.** Nothing is stored except the trained `.joblib` files.
- **`comments` and `like_counts` are positional.** A shorter `like_counts` is padded with
  zeros rather than rejected, so a mismatch fails silently — the backend always sends both
  the same length.
- **`/ml/demand` can be slow** on a large comment set. The backend allows 180 s.

## 8. Tests

```bash
pip install pytest      # not in either requirements file
pytest
```

Covers `preprocessing`, `models` and `topics`.
`demand.py` — the module everything else exists to serve — has **no test coverage**; if
you touch `REQUEST_PATTERNS` or `_tidy_subject()`, that is the gap to fill first. The
`_ART` regex note in §2 is exactly the kind of regression a test there would have caught.

Inside the running stack: `docker compose exec ml-service pytest`.
