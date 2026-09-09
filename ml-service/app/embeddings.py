"""
Sentence embeddings with graceful degradation.

Primary: sentence-transformers ('all-MiniLM-L6-v2') for high-quality semantic
embeddings. If the library or model weights are unavailable (e.g. offline / no
model download), we fall back to a TF-IDF character+word vectorizer so that
similarity, clustering and topic modeling still function. The active backend is
reported via `backend_name()`.
"""
from typing import List

import numpy as np

_st_model = None
_tfidf = None
_use_st = None


def _try_load_st():
    global _st_model, _use_st
    if _use_st is not None:
        return _use_st
    try:
        from sentence_transformers import SentenceTransformer  # type: ignore
        model_name = "all-MiniLM-L6-v2"
        _st_model = SentenceTransformer(model_name)
        _use_st = True
    except Exception:
        _use_st = False
    return _use_st


def backend_name() -> str:
    return "sentence-transformers" if _try_load_st() else "tfidf-fallback"


def _fit_tfidf(corpus: List[str]):
    from sklearn.feature_extraction.text import TfidfVectorizer
    global _tfidf
    _tfidf = TfidfVectorizer(ngram_range=(1, 2), min_df=1, sublinear_tf=True)
    _tfidf.fit(corpus if corpus else ["placeholder text"])


def embed(texts: List[str]) -> np.ndarray:
    """Return an (n, d) L2-normalized embedding matrix."""
    if not texts:
        return np.zeros((0, 384), dtype=np.float32)
    if _try_load_st():
        vecs = _st_model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
        return np.asarray(vecs, dtype=np.float32)
    # TF-IDF fallback (fit lazily on whatever corpus we see).
    if _tfidf is None:
        _fit_tfidf(texts)
    mat = _tfidf.transform(texts).toarray().astype(np.float32)
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return mat / norms


def cosine_matrix(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    if a.shape[0] == 0 or b.shape[0] == 0:
        return np.zeros((a.shape[0], b.shape[0]), dtype=np.float32)
    return np.clip(a @ b.T, -1.0, 1.0)
