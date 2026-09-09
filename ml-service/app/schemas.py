"""Pydantic request/response schemas for the ML API."""
from typing import List, Optional

from pydantic import BaseModel


class CommentsRequest(BaseModel):
    comments: List[str]


class SentimentResult(BaseModel):
    text: str
    sentiment: str
    score: float


class SentimentResponse(BaseModel):
    results: List[SentimentResult]


class IntentResult(BaseModel):
    text: str
    intent: str
    score: float


class IntentResponse(BaseModel):
    results: List[IntentResult]


class TopicsRequest(BaseModel):
    texts: List[str]
    min_topic_size: int = 5


class TopicInfo(BaseModel):
    topic_id: int
    label: str
    keywords: List[str]
    size: int
    representative_docs: List[str]


class TopicsResponse(BaseModel):
    topics: List[TopicInfo]
    assignments: List[int]
    backend: Optional[str] = None


class TextsRequest(BaseModel):
    texts: List[str]


class RequestCluster(BaseModel):
    label: str
    size: int
    examples: List[str]


class ClusterResponse(BaseModel):
    clusters: List[RequestCluster]


class SimilarityRequest(BaseModel):
    query: str
    corpus: List[str]


class SimilarityResponse(BaseModel):
    max_similarity: float
    mean_similarity: float


class DemandRequest(BaseModel):
    """Comments for one channel, optionally with their like counts."""
    comments: List[str]
    like_counts: Optional[List[int]] = None
    max_topics: int = 12


class DemandTopic(BaseModel):
    label: str
    demand_score: float
    request_count: int
    mention_count: int
    examples: List[str] = []
    source: str


class MentionedTerm(BaseModel):
    term: str
    mentions: int
    score: float


class DemandResponse(BaseModel):
    demand_topics: List[DemandTopic]
    mentioned_terms: List[MentionedTerm] = []
    comments_analyzed: int
    requests_found: int
    backend: Optional[str] = None
