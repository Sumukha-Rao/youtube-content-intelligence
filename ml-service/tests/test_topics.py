from app import topics


CORPUS = [
    "How do Kafka consumer groups work",
    "Kafka partitions and offsets explained",
    "Please make a Kafka tutorial with Spring Boot",
    "Docker compose multi container setup",
    "Dockerfile best practices for images",
    "How to reduce Docker image size",
    "Deploy a Lambda function on AWS",
    "AWS S3 and Lambda integration",
    "Serverless AWS architecture guide",
]


def test_topic_discovery_returns_topics_and_assignments():
    result = topics.discover_topics(CORPUS, min_topic_size=2)
    assert "topics" in result and "assignments" in result
    assert len(result["assignments"]) == len(CORPUS)
    assert len(result["topics"]) >= 2
    for t in result["topics"]:
        assert t["size"] >= 2
        assert isinstance(t["keywords"], list)


def test_similarity_query_matches_related_corpus():
    high = topics.similarity("Kafka streaming tutorial", CORPUS)["max_similarity"]
    low = topics.similarity("cooking pasta recipe", CORPUS)["max_similarity"]
    assert 0.0 <= low <= high <= 1.0
    assert high > low


def test_cluster_requests():
    reqs = [
        "Make a Kafka tutorial",
        "Please explain Kafka",
        "Can you teach Kafka",
        "Do a Docker networking video",
        "Cover Docker volumes please",
    ]
    result = topics.cluster_requests(reqs)
    assert "clusters" in result
    assert sum(c["size"] for c in result["clusters"]) == len(reqs)
