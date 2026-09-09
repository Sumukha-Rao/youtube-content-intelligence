from app import models


def test_sentiment_predictions():
    models.train_sentiment()
    res = models.predict_sentiment([
        "This tutorial was excellent and very clear",
        "Awful editing, hard to watch and confusing",
    ])
    assert res[0]["sentiment"] == "POSITIVE"
    assert res[1]["sentiment"] == "NEGATIVE"
    assert 0.0 <= res[0]["score"] <= 1.0


def test_intent_predictions():
    models.train_intent()
    res = models.predict_intent([
        "Please make a video on Kafka with Spring Boot",
        "How do I configure the database connection",
        "This tutorial was excellent thank you",
    ])
    intents = [r["intent"] for r in res]
    assert intents[0] == "CONTENT_REQUEST"
    assert intents[1] == "QUESTION"
    assert intents[2] == "PRAISE"


def test_labels_are_valid():
    res = models.predict_sentiment(["some neutral statement about docker"])
    assert res[0]["sentiment"] in models.SENTIMENT_LABELS
