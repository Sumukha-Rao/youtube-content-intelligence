"""The existing fallback must analyze each request's own vocabulary."""
import numpy as np
from app import embeddings, topics


def test_fallback_does_not_keep_the_first_requests_vocabulary(monkeypatch):
    monkeypatch.setattr(embeddings, '_use_st', False)
    monkeypatch.setattr(embeddings, '_tfidf', None, raising=False)
    first = embeddings.embed(['Kafka consumer groups'])
    second = embeddings.embed(['Docker networking'])
    assert np.linalg.norm(first[0]) > 0
    assert np.linalg.norm(second[0]) > 0


def test_fallback_similarity_handles_unrelated_sequential_requests(monkeypatch):
    monkeypatch.setattr(embeddings, '_use_st', False)
    monkeypatch.setattr(embeddings, '_tfidf', None, raising=False)
    topics.similarity('Kafka', ['Kafka consumers'])
    result = topics.similarity('Docker networking', ['Docker networking guide', 'Cooking pasta'])
    assert result['max_similarity'] > 0.5


def test_fallback_empty_vocabulary_does_not_crash(monkeypatch):
    monkeypatch.setattr(embeddings, '_use_st', False)
    monkeypatch.setattr(embeddings, '_tfidf', None, raising=False)
    result = embeddings.embed(['!', '?'])
    assert result.shape[0] == 2
    assert np.isfinite(result).all()
