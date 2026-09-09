from app.preprocessing import clean, is_spam, detect_language, preprocess_batch


def test_url_and_whitespace_removed():
    out = clean("Check   this   https://spam.example.com    out")
    assert "http" not in out
    assert "  " not in out


def test_technical_terms_preserved():
    text = "Please cover Spring Boot and Kubernetes with Kafka and PostgreSQL"
    out = clean(text)
    for term in ["Spring", "Boot", "Kubernetes", "Kafka", "PostgreSQL"]:
        assert term in out


def test_excessive_punctuation_and_chars():
    assert clean("soooooo good!!!!!") == "soo good!"


def test_spam_detection():
    assert is_spam("sub4sub check out my channel")
    assert not is_spam("Great tutorial on Kafka consumer groups")


def test_language_detection_english():
    assert detect_language("This is an English sentence about Docker") == "en"


def test_batch_dedup_and_index_mapping():
    texts = ["Great video", "Great video", "sub4sub", "Nice Kafka demo"]
    kept, idx = preprocess_batch(texts)
    # duplicate and spam removed
    assert kept == ["Great video", "Nice Kafka demo"]
    assert idx == [0, 3]
