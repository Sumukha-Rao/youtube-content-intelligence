"""
FastAPI ML/NLP service for the YouTube Content Intelligence Platform.

Endpoints. The Spring Boot backend calls only /health and /ml/demand; the rest
are standalone analysis endpoints kept for inspection and reuse (see README.md):
  GET  /health
  POST /ml/sentiment     -> TF-IDF + Logistic Regression
  POST /ml/intent        -> TF-IDF + Linear SVM (calibrated)
  POST /ml/topics        -> BERTopic (embeddings + HDBSCAN) with fallback
  POST /ml/cluster-requests -> semantic clustering of content requests
  POST /ml/similarity    -> semantic similarity of a query vs a corpus
  POST /ml/demand        -> ranked "what should I make next" demand topics
  POST /ml/train         -> retrain sentiment + intent from the seed CSVs
"""
import logging

from fastapi import FastAPI

from . import demand, embeddings, models, topics
from .preprocessing import preprocess_batch
from .schemas import (ClusterResponse, CommentsRequest, DemandRequest,
                      DemandResponse, IntentResponse, SentimentResponse,
                      SimilarityRequest, SimilarityResponse, TextsRequest,
                      TopicsRequest, TopicsResponse)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("ml-service")

app = FastAPI(title="YCI ML Service", version="1.0.0")


@app.on_event("startup")
def warmup():
    # Train (or load) the classical models eagerly so the first request is fast.
    try:
        models._get_sentiment()
        models._get_intent()
        log.info("Sentiment + intent models ready. Embedding backend: %s", embeddings.backend_name())
    except Exception as e:  # pragma: no cover
        log.warning("Model warmup failed: %s", e)


@app.get("/health")
def health():
    return {"status": "UP", "embedding_backend": embeddings.backend_name()}


@app.post("/ml/sentiment", response_model=SentimentResponse)
def sentiment(req: CommentsRequest):
    if not req.comments:
        return {"results": []}
    return {"results": models.predict_sentiment(req.comments)}


@app.post("/ml/intent", response_model=IntentResponse)
def intent(req: CommentsRequest):
    if not req.comments:
        return {"results": []}
    return {"results": models.predict_intent(req.comments)}


@app.post("/ml/topics", response_model=TopicsResponse)
def discover_topics(req: TopicsRequest):
    # Preprocess (spam/duplicate filtering) but keep alignment to original indices.
    return topics.discover_topics(req.texts, req.min_topic_size)


@app.post("/ml/cluster-requests", response_model=ClusterResponse)
def cluster_requests(req: TextsRequest):
    kept, _ = preprocess_batch(req.texts, drop_spam=True)
    return topics.cluster_requests(kept if kept else req.texts)


@app.post("/ml/similarity", response_model=SimilarityResponse)
def similarity(req: SimilarityRequest):
    return topics.similarity(req.query, req.corpus)


@app.post("/ml/demand", response_model=DemandResponse)
def audience_demand(req: DemandRequest):
    """Rank what this audience is asking to see next.

    Explicit asks ("make a video on X", "I want to know about X") dominate the
    ranking; frequently-discussed terms fill in behind them. Results feed the
    prompt builder and are never displayed to the user.
    """
    return demand.analyze_demand(req.comments, req.like_counts, req.max_topics)


@app.post("/ml/train")
def train():
    return models.train_all()
