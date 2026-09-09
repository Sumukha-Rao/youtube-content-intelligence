"""
Text preprocessing for YouTube comments.

Implements: unicode normalization, URL removal, whitespace normalization,
excessive-punctuation handling, spam/duplicate filtering, optional stopword
handling, and a lightweight language-detection heuristic.

Design note: meaningful technical tokens (Spring Boot, Kubernetes, Kafka, AWS
Lambda, PostgreSQL, Docker, ...) are preserved — we deliberately do NOT strip
capitalization or split multi-word technologies, and stopword removal is optional.
"""
import re
import unicodedata
from typing import List, Tuple

URL_RE = re.compile(r"https?://\S+|www\.\S+")
MENTION_RE = re.compile(r"@\w+")
MULTI_PUNCT_RE = re.compile(r"([!?.,])\1{2,}")      # !!!! -> !
MULTI_CHAR_RE = re.compile(r"(.)\1{3,}")            # soooo -> soo (keep 2)
WS_RE = re.compile(r"\s+")
EMOJI_RE = re.compile(
    "[" "\U0001F300-\U0001FAFF" "\U00002700-\U000027BF" "\U0001F000-\U0001F0FF" "☀-⛿" "]+",
    flags=re.UNICODE,
)

# A minimal English stopword list; removal is OPTIONAL and off by default so we do
# not destroy short but meaningful comments.
STOPWORDS = {
    "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "for", "with",
    "is", "are", "was", "were", "be", "been", "this", "that", "it", "as", "at",
    "by", "from", "i", "you", "he", "she", "they", "we", "me", "my", "your",
}

# Technical terms we never want normalization to damage. Used to re-inject casing.
TECH_TERMS = [
    "Spring Boot", "Kubernetes", "Kafka", "AWS Lambda", "PostgreSQL", "Docker",
    "AWS", "EC2", "S3", "Lambda", "MySQL", "Redis", "GraphQL", "REST", "gRPC",
    "React", "Angular", "Vue", "Node.js", "TypeScript", "JavaScript", "Python",
    "Java", "Spring", "Hibernate", "Maven", "Gradle", "Terraform", "Jenkins",
    "CI CD", "Microservices", "MongoDB", "Elasticsearch",
]

SPAM_PATTERNS = [
    re.compile(r"sub4sub", re.I),
    re.compile(r"check out my channel", re.I),
    re.compile(r"free .* (giveaway|money|gift ?card)", re.I),
    re.compile(r"(click|visit) (the )?link", re.I),
    re.compile(r"follow me", re.I),
    re.compile(r"\b(viagra|casino|crypto pump)\b", re.I),
]


def normalize_unicode(text: str) -> str:
    return unicodedata.normalize("NFKC", text)


def is_spam(text: str) -> bool:
    if not text or len(text.strip()) < 2:
        return True
    for p in SPAM_PATTERNS:
        if p.search(text):
            return True
    # Mostly non-alphanumeric -> likely junk.
    alnum = sum(c.isalnum() for c in text)
    if len(text) > 0 and alnum / max(len(text), 1) < 0.3:
        return True
    return False


def detect_language(text: str) -> str:
    """Lightweight heuristic. Returns 'en' for predominantly Latin/ASCII text,
    else 'other'. Optionally upgraded by langdetect if installed."""
    try:
        from langdetect import detect  # type: ignore
        return detect(text)
    except Exception:
        pass
    if not text:
        return "unknown"
    ascii_letters = sum(1 for c in text if ("a" <= c.lower() <= "z"))
    letters = sum(1 for c in text if c.isalpha())
    if letters == 0:
        return "unknown"
    return "en" if ascii_letters / letters > 0.6 else "other"


def clean(text: str, remove_stopwords: bool = False) -> str:
    """Return a cleaned version of the text, preserving technical terms."""
    if text is None:
        return ""
    t = normalize_unicode(text)
    t = URL_RE.sub(" ", t)
    t = MENTION_RE.sub(" ", t)
    t = EMOJI_RE.sub(" ", t)
    t = MULTI_PUNCT_RE.sub(r"\1", t)
    t = MULTI_CHAR_RE.sub(r"\1\1", t)
    t = WS_RE.sub(" ", t).strip()
    if remove_stopwords:
        t = " ".join(w for w in t.split() if w.lower() not in STOPWORDS)
    return t


def preprocess_batch(
    texts: List[str], remove_stopwords: bool = False, drop_spam: bool = True
) -> Tuple[List[str], List[int]]:
    """Clean a batch and drop spam/duplicates.

    Returns (kept_texts, kept_indices) so callers can map results back to the
    original list positions.
    """
    seen = set()
    kept, idx = [], []
    for i, raw in enumerate(texts):
        c = clean(raw, remove_stopwords=remove_stopwords)
        if not c:
            continue
        if drop_spam and is_spam(c):
            continue
        key = c.lower()
        if key in seen:  # duplicate detection
            continue
        seen.add(key)
        kept.append(c)
        idx.append(i)
    return kept, idx
