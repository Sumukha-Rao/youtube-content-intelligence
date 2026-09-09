"""
Topic discovery, request clustering and semantic similarity.

Primary path uses BERTopic (sentence embeddings + HDBSCAN + c-TF-IDF). When those
heavy dependencies or model weights are unavailable, we fall back to embedding the
texts with the TF-IDF backend and clustering with scikit-learn (KMeans /
Agglomerative), extracting per-cluster keywords with TF-IDF. Either way the API
contract (topics + assignments) is identical.
"""
from typing import Dict, List

import numpy as np
from sklearn.cluster import AgglomerativeClustering, KMeans
from sklearn.feature_extraction.text import CountVectorizer, TfidfVectorizer

from . import embeddings
from .preprocessing import clean
from .stopwords import LABEL_STOPWORDS, content_tokens


def _keywords_for(docs: List[str], top_n: int = 6) -> List[str]:
    if not docs:
        return []
    try:
        vec = TfidfVectorizer(ngram_range=(1, 2), min_df=1,
                              stop_words=list(LABEL_STOPWORDS))
        mat = vec.fit_transform(docs)
        scores = np.asarray(mat.sum(axis=0)).ravel()
        terms = np.array(vec.get_feature_names_out())
        order = np.argsort(scores)[::-1]
        # Only keep terms that still carry meaning once filler is stripped, so a
        # cluster can never be labelled with something like "to the".
        return [str(terms[i]) for i in order if content_tokens(str(terms[i]))][:top_n]
    except Exception:
        return []


def _label_from_keywords(keywords: List[str]) -> str:
    picked = [k for k in keywords if content_tokens(k)][:2]
    if not picked:
        return "General"
    return " / ".join(w.title() for w in picked)


def _representative_docs(docs: List[str], vectors: np.ndarray, member_idx: List[int], k: int = 3):
    if not member_idx:
        return []
    sub = vectors[member_idx]
    centroid = sub.mean(axis=0, keepdims=True)
    sims = (sub @ centroid.T).ravel()
    order = np.argsort(sims)[::-1][:k]
    return [docs[member_idx[i]] for i in order]


def discover_topics(texts: List[str], min_topic_size: int = 5) -> Dict:
    cleaned = [clean(t) for t in texts]
    keep = [i for i, c in enumerate(cleaned) if c]
    docs = [cleaned[i] for i in keep]
    if len(docs) < max(6, min_topic_size):
        return {"topics": [], "assignments": [-1] * len(texts), "backend": "insufficient-data"}

    # ---- Primary: BERTopic ----
    try:
        from bertopic import BERTopic  # type: ignore
        if embeddings._try_load_st():
            from sentence_transformers import SentenceTransformer  # noqa
            # The c-TF-IDF representation MUST filter stopwords, otherwise topic
            # labels degenerate into pure filler ("to / the").
            topic_model = BERTopic(
                min_topic_size=max(2, min_topic_size), verbose=False,
                vectorizer_model=CountVectorizer(stop_words=list(LABEL_STOPWORDS),
                                                 ngram_range=(1, 2), min_df=1))
            embs = embeddings.embed(docs)
            labels, _ = topic_model.fit_transform(docs, embeddings=embs)
            info = topic_model.get_topic_info()
            topics = []
            for _, row in info.iterrows():
                tid = int(row["Topic"])
                if tid == -1:
                    continue
                words = [w for w, _ in topic_model.get_topic(tid) if content_tokens(w)][:6]
                reps = topic_model.get_representative_docs(tid)[:3]
                topics.append({
                    "topic_id": tid,
                    "label": _label_from_keywords(words),
                    "keywords": words,
                    "size": int(row["Count"]),
                    "representative_docs": reps,
                })
            assignments = _expand(labels, keep, len(texts))
            return {"topics": topics, "assignments": assignments, "backend": "bertopic"}
    except Exception:
        pass  # fall through to sklearn fallback

    # ---- Fallback: embeddings + clustering ----
    vectors = embeddings.embed(docs)
    n = len(docs)
    n_clusters = max(2, min(10, n // max(min_topic_size, 3)))
    try:
        labels = AgglomerativeClustering(n_clusters=n_clusters).fit_predict(vectors)
    except Exception:
        labels = KMeans(n_clusters=n_clusters, n_init=10, random_state=42).fit_predict(vectors)

    topics = []
    for tid in sorted(set(labels)):
        member_idx = [i for i, l in enumerate(labels) if l == tid]
        if len(member_idx) < 2:
            continue
        member_docs = [docs[i] for i in member_idx]
        kws = _keywords_for(member_docs)
        topics.append({
            "topic_id": int(tid),
            "label": _label_from_keywords(kws),
            "keywords": kws,
            "size": len(member_idx),
            "representative_docs": _representative_docs(docs, vectors, member_idx),
        })
    assignments = _expand(list(labels), keep, len(texts))
    return {"topics": topics, "assignments": assignments, "backend": embeddings.backend_name()}


def cluster_requests(texts: List[str]) -> Dict:
    """Group semantically similar content requests. Returns clusters with sizes/examples."""
    cleaned = [clean(t) for t in texts]
    keep = [i for i, c in enumerate(cleaned) if c]
    docs = [cleaned[i] for i in keep]
    if len(docs) < 2:
        return {"clusters": [{"label": d, "size": 1, "examples": [d]} for d in docs]}

    vectors = embeddings.embed(docs)
    n_clusters = max(1, min(12, len(docs) // 3 or 1))
    try:
        labels = AgglomerativeClustering(n_clusters=n_clusters).fit_predict(vectors)
    except Exception:
        labels = KMeans(n_clusters=n_clusters, n_init=10, random_state=42).fit_predict(vectors)

    clusters = []
    for tid in sorted(set(labels)):
        member_idx = [i for i, l in enumerate(labels) if l == tid]
        member_docs = [docs[i] for i in member_idx]
        kws = _keywords_for(member_docs, top_n=4)
        examples = _representative_docs(docs, vectors, member_idx, k=3)
        clusters.append({
            "label": _label_from_keywords(kws) if kws else member_docs[0],
            "size": len(member_idx),
            "examples": examples or member_docs[:3],
        })
    clusters.sort(key=lambda c: c["size"], reverse=True)
    return {"clusters": clusters}


def similarity(query: str, corpus: List[str]) -> Dict:
    q = clean(query)
    corp = [clean(c) for c in corpus if clean(c)]
    if not q or not corp:
        return {"max_similarity": 0.0, "mean_similarity": 0.0}
    all_vecs = embeddings.embed([q] + corp)
    qv = all_vecs[:1]
    cv = all_vecs[1:]
    sims = embeddings.cosine_matrix(qv, cv).ravel()
    return {"max_similarity": round(float(np.max(sims)), 4),
            "mean_similarity": round(float(np.mean(sims)), 4)}


def _expand(labels: List[int], keep: List[int], total: int) -> List[int]:
    """Map cluster labels back to original text positions (-1 for dropped/empty)."""
    out = [-1] * total
    for pos, lab in zip(keep, labels):
        out[pos] = int(lab)
    return out
