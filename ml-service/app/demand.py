"""
Audience demand extraction — "what does this audience want to see next?".

The pipeline is deliberately rule-first, because an explicit ask is by far the
strongest signal a comment section produces:

  1. Match every comment against REQUEST_PATTERNS ("make a video on X",
     "I want to know about X", "can you cover X", ...) and capture the *subject*
     of the request (X) rather than the boilerplate wrapped around it.
  2. Weight each hit by how explicit the phrasing is, with a mild boost from the
     comment's like count (other viewers agreeing with the ask).
  3. Cluster the captured subjects semantically so paraphrases collapse together
     ("kafka consumer groups" and "consumer group rebalancing" become one topic).
  4. Label each cluster from its own phrases with aggressive stopword filtering,
     so a label can never come out as "To / The".

A weaker secondary signal — frequent content-bearing n-grams across *all*
comments — is folded in afterwards, so subjects the audience keeps discussing
without ever phrasing them as a request still surface.

Output is consumed by the backend prompt builder and is never shown to the user.
"""
import math
import re
from collections import defaultdict
from typing import Dict, List, Optional

import numpy as np
from sklearn.cluster import AgglomerativeClustering
from sklearn.feature_extraction.text import TfidfVectorizer

from . import embeddings
from .preprocessing import clean
from .stopwords import (LABEL_STOPWORDS, SUBJECT_LEAD_STRIP, SUBJECT_TAIL_STRIP,
                        content_tokens, has_content, topic_key)

# The captured subject: stop at sentence punctuation or end of comment.
_SUBJ = r"(.{3,140}?)(?:[.!?\n]|$)"
# Optional article / content-noun prefixes. These MUST consume a trailing space
# rather than using `\s*`, otherwise `(?:a|an|the)?` happily eats the leading "A"
# of "AWS" and the captured subject comes back as "WS Lambda".
_ART = r"(?:(?:a|an|the)\s+)?"
_NOUN = r"(?:(?:video|tutorial|series|guide|vid|content)\s+)?"

# (pattern, weight). Higher weight = more unambiguously a request for future content.
REQUEST_PATTERNS = [
    # "please make a video on X" / "make a tutorial about X"
    (re.compile(r"\b(?:please\s+|pls\s+|plz\s+|kindly\s+)?mak(?:e|ing)\s+" + _ART + _NOUN +
                r"(?:on|about|for|regarding)\s+" + _SUBJ, re.I), 3.0),
    # "do a video on X"
    (re.compile(r"\b(?:please\s+|pls\s+|plz\s+)?do\s+" + _ART +
                r"(?:video|tutorial|series|guide)\s+(?:on|about|for)\s+" + _SUBJ, re.I), 3.0),
    # "can you make/do/cover/explain X" (and could/would/will you ...)
    (re.compile(r"\b(?:can|could|would|will)\s+(?:you|u|ya)\s+(?:please\s+|pls\s+|plz\s+)?"
                r"(?:make|do|create|upload|post|cover|explain|show|teach|talk\s+about|discuss|review)\s+"
                r"(?:us\s+|me\s+)?" + _ART + _NOUN +
                r"(?:(?:on|about|for|regarding)\s+)?" + _SUBJ, re.I), 3.0),
    # "I want to know about X" / "I'd love to learn X"
    (re.compile(r"\bi\s+(?:really\s+|just\s+)?(?:want|wanna|would\s+like|'d\s+like|need)\s+to\s+"
                r"(?:know|learn|see|understand|watch)\s+(?:more\s+)?(?:(?:about|on)\s+)?" + _SUBJ, re.I), 3.0),
    # "I want a video on X" / "we need a video about X"
    (re.compile(r"\b(?:i|we)\s+(?:really\s+)?(?:want|need|wish\s+for)\s+" + _ART +
                r"(?:video|tutorial|series|guide)\s+(?:on|about|for)\s+" + _SUBJ, re.I), 3.0),
    # "I would love to see X" / "would love a video on X"
    (re.compile(r"\b(?:i\s+)?(?:would|'d)\s+love\s+(?:to\s+see|to\s+watch|a\s+video\s+(?:on|about))\s+"
                + _ART + _SUBJ, re.I), 2.6),
    # "please upload/post X"
    (re.compile(r"\b(?:please\s+|pls\s+|plz\s+)?(?:upload|post)\s+" + _ART +
                r"(?:video\s+)?(?:on|about)\s+" + _SUBJ, re.I), 2.6),
    # "next video on X" / "another video about X"
    (re.compile(r"\b(?:next|another|following|upcoming)\s+(?:video|tutorial|part|episode)\s+"
                r"(?:on|about|should\s+be|could\s+be)\s+" + _SUBJ, re.I), 2.6),
    # "more videos on X" / "more about X"
    (re.compile(r"\bmore\s+(?:videos?\s+|content\s+)?(?:on|about)\s+" + _SUBJ, re.I), 2.3),
    # "video idea: X" / "suggestion - X"
    (re.compile(r"\b(?:video\s+)?(?:idea|suggestion|request)\s*[:\-–]\s*" + _SUBJ, re.I), 2.5),
    # "when will you cover X"
    (re.compile(r"\bwhen\s+(?:will|are|r)\s+(?:you|u)\s+(?:going\s+to\s+|gonna\s+)?"
                r"(?:make|do|cover|upload|post|talk\s+about)\s+(?:a\s+video\s+(?:on|about)\s+)?" + _SUBJ, re.I), 2.2),
    # "waiting for X"
    (re.compile(r"\b(?:still\s+)?waiting\s+for\s+(?:a\s+|the\s+)?(?:video\s+)?(?:on|about)?\s*" + _SUBJ, re.I), 2.0),
    # bare imperative: "explain X" / "cover X" / "review X"
    (re.compile(r"\b(?:please\s+|pls\s+|plz\s+)?(?:explain|cover|review|teach\s+us|demonstrate)\s+"
                + _ART + _SUBJ, re.I), 1.8),
    # questions — real curiosity, but a weaker signal than an explicit ask
    (re.compile(r"\bhow\s+(?:do|does|to|can|would)\s+(?:you|i|we|one)?\s*" + _SUBJ, re.I), 1.2),
    (re.compile(r"\b(?:what|why)\s+(?:is|are|was|were|does|do)\s+(?:the\s+)?" + _SUBJ, re.I), 1.0),
]

# A subject made only of these is a false positive ("can you make it", "explain this").
_JUNK_SUBJECTS = {
    "it", "this", "that", "them", "these", "those", "something", "anything",
    "everything", "more", "again", "us", "me", "you", "one", "part", "next",
}


def _tidy_subject(raw: str) -> Optional[str]:
    """Trim a captured request subject down to its topical core.

    Returns None when nothing meaningful survives (pure filler, too short, ...).
    """
    if not raw:
        return None
    s = clean(raw).strip().strip("\"'“”‘’()[]{}<>").strip()
    s = re.sub(r"\s+", " ", s)
    if not s:
        return None

    words = s.split()
    # Strip leading filler ("a video on kafka" -> "kafka").
    while words and words[0].lower().strip(",.:;!?") in SUBJECT_LEAD_STRIP:
        words.pop(0)
    # Strip trailing filler ("kafka please bro" -> "kafka").
    while words and words[-1].lower().strip(",.:;!?") in SUBJECT_TAIL_STRIP:
        words.pop()
    if not words:
        return None

    s = " ".join(words).strip(" ,.:;!?-–")
    if len(s) < 3 or len(words) > 14:
        return None
    if s.lower() in _JUNK_SUBJECTS:
        return None
    if not has_content(s):
        return None
    return s[:120]


def extract_requests(comments: List[str], like_counts: Optional[List[int]] = None) -> List[Dict]:
    """Pull explicit "please cover X" style asks out of a list of comments."""
    likes = like_counts or [0] * len(comments)
    out: List[Dict] = []
    for i, raw in enumerate(comments):
        if not raw:
            continue
        text = clean(raw)
        if not text:
            continue
        best = None
        for pattern, weight in REQUEST_PATTERNS:
            m = pattern.search(text)
            if not m:
                continue
            subject = _tidy_subject(m.group(1))
            if not subject:
                continue
            # Keep the highest-weight interpretation of a given comment only, so a
            # single comment cannot stuff the ranking with near-duplicate subjects.
            if best is None or weight > best["weight"]:
                best = {"subject": subject, "weight": weight}
        if best is None:
            continue
        like = likes[i] if i < len(likes) and likes[i] else 0
        # Crowd agreement: 10 likes ~ +0.6, 100 likes ~ +1.2. Deliberately gentle.
        like_boost = 0.6 * math.log10(1 + max(0, like))
        out.append({
            "subject": best["subject"],
            "weight": round(best["weight"] + like_boost, 4),
            "comment": raw[:400],
            "likes": int(like),
        })
    return out


def _best_label(phrases: List[str], vectors: np.ndarray, member_idx: List[int]) -> str:
    """Human-readable label for a cluster, built only from content-bearing words."""
    members = [phrases[i] for i in member_idx]

    # If several phrases reduce to the same content words, that repeated form wins.
    by_key = defaultdict(list)
    for p in members:
        key = " ".join(sorted(set(content_tokens(p))))
        if key:
            by_key[key].append(p)
    if by_key:
        best_key = max(by_key, key=lambda k: (len(by_key[k]), -len(min(by_key[k], key=len))))
        if len(by_key[best_key]) > 1:
            return _title(min(by_key[best_key], key=len))

    # Otherwise take the phrase closest to the cluster centroid, preferring short ones.
    sub = vectors[member_idx]
    centroid = sub.mean(axis=0, keepdims=True)
    sims = (sub @ centroid.T).ravel()
    order = np.argsort(sims)[::-1]
    for j in order[:5]:
        cand = members[int(j)]
        if has_content(cand):
            return _title(cand)
    return _title(members[0])


# Restored to upper case in labels, since commenters rarely capitalise them.
ACRONYMS = {
    "aws", "gcp", "api", "apis", "ai", "ml", "llm", "llms", "rag", "gpu", "cpu",
    "ssd", "hdd", "ram", "os", "ci", "cd", "sql", "nosql", "css", "html", "js",
    "ts", "ui", "ux", "http", "https", "dns", "vpn", "usb", "pc", "tv", "3d",
    "seo", "sdk", "ide", "orm", "jwt", "ssh", "ssl", "tls", "vm", "vms", "k8s",
    "crud", "mvc", "rest", "grpc", "json", "xml", "yaml", "csv", "iot", "ar", "vr",
}


def _title(phrase: str) -> str:
    """Title-case a label without mangling acronyms (AWS, GPU, CI/CD) or versions."""
    words = []
    for w in phrase.split():
        bare = w.strip(".,:;!?").lower()
        if w.isupper() and len(w) <= 5:
            words.append(w)
        elif bare in ACRONYMS:
            words.append(w.upper())
        elif any(c.isdigit() for c in w):
            words.append(w)
        else:
            words.append(w[:1].upper() + w[1:])
    return " ".join(words)[:80]


def _mentioned_terms(comments: List[str], top_n: int = 15) -> List[Dict]:
    """Frequent content-bearing phrases across all comments (weak demand signal).

    Only multi-word phrases qualify. A bare unigram is almost never a usable video
    topic — "Price", "Run", "Linus" are noise that also poisons the web searches
    built from these labels — whereas "cable management" or "steam deck" is real.
    """
    docs = [clean(c) for c in comments]
    docs = [d for d in docs if d and has_content(d)]
    if len(docs) < 8:
        return []
    try:
        vec = TfidfVectorizer(
            ngram_range=(2, 3), min_df=3, max_df=0.4, sublinear_tf=True,
            stop_words=list(LABEL_STOPWORDS), token_pattern=r"(?u)\b[a-zA-Z][a-zA-Z0-9+#.\-]{1,}\b",
        )
        mat = vec.fit_transform(docs)
    except ValueError:
        return []  # vocabulary empty after filtering
    scores = np.asarray(mat.sum(axis=0)).ravel()
    counts = np.asarray((mat > 0).sum(axis=0)).ravel()
    terms = np.array(vec.get_feature_names_out())
    order = np.argsort(scores)[::-1]

    out, seen = [], set()
    for i in order:
        term = str(terms[i])
        toks = content_tokens(term)
        if len(toks) < 2:
            continue
        key = topic_key(term)
        if not key or key in seen:  # "docker network" vs "network dockers"
            continue
        seen.add(key)
        out.append({"term": _title(term), "mentions": int(counts[i]), "score": round(float(scores[i]), 4)})
        if len(out) >= top_n:
            break
    return out


def analyze_demand(comments: List[str], like_counts: Optional[List[int]] = None,
                   max_topics: int = 12) -> Dict:
    """Full demand analysis for one channel's comments.

    Returns ranked demand topics plus supporting counts. Everything here feeds the
    LLM prompt; none of it is rendered for the user.
    """
    comments = [c for c in (comments or []) if c and str(c).strip()]
    if not comments:
        return {"demand_topics": [], "mentioned_terms": [], "comments_analyzed": 0,
                "requests_found": 0, "backend": "empty"}

    requests = extract_requests(comments, like_counts)
    mentioned = _mentioned_terms(comments)

    if not requests:
        # No explicit asks: fall back to what the audience talks about most.
        topics = [{
            "label": t["term"],
            "demand_score": round(t["score"], 4),
            "request_count": 0,
            "mention_count": t["mentions"],
            "examples": [],
            "source": "mentions",
        } for t in mentioned[:max_topics]]
        return {"demand_topics": topics, "mentioned_terms": mentioned,
                "comments_analyzed": len(comments), "requests_found": 0,
                "backend": embeddings.backend_name()}

    phrases = [r["subject"] for r in requests]
    vectors = embeddings.embed(phrases)

    # Cluster paraphrases together. One cluster per ~2 requests, capped, so a small
    # comment section still yields distinct topics rather than one giant blob.
    if len(phrases) >= 4:
        n_clusters = max(2, min(max_topics * 2, len(phrases) // 2))
        try:
            labels = AgglomerativeClustering(n_clusters=n_clusters).fit_predict(vectors)
        except Exception:
            labels = list(range(len(phrases)))
    else:
        labels = list(range(len(phrases)))

    grouped: Dict[int, List[int]] = defaultdict(list)
    for i, lab in enumerate(labels):
        grouped[int(lab)].append(i)

    topics = []
    for lab, member_idx in grouped.items():
        score = sum(requests[i]["weight"] for i in member_idx)
        examples = [requests[i]["comment"] for i in
                    sorted(member_idx, key=lambda i: -requests[i]["weight"])[:3]]
        topics.append({
            "label": _best_label(phrases, vectors, member_idx),
            "demand_score": round(float(score), 4),
            "request_count": len(member_idx),
            "mention_count": len(member_idx),
            "examples": examples,
            "source": "requests",
        })

    # Fold in frequently-mentioned terms that no one explicitly requested. A term
    # whose words are already covered by a request topic ("consumer" under "Kafka
    # Consumer Group Rebalancing") adds nothing, so drop it.
    known_sets = [set(content_tokens(t["label"])) for t in topics]
    known = {topic_key(t["label"]) for t in topics}
    for t in mentioned:
        toks = set(content_tokens(t["term"]))
        key = topic_key(t["term"])
        if toks and any(toks <= s for s in known_sets):
            continue
        if key and key not in known:
            topics.append({
                "label": t["term"],
                "demand_score": round(float(t["score"]) * 0.5, 4),
                "request_count": 0,
                "mention_count": t["mentions"],
                "examples": [],
                "source": "mentions",
            })
            known.add(key)
            known_sets.append(toks)

    topics.sort(key=lambda t: (-t["demand_score"], -t["request_count"]))
    return {
        "demand_topics": topics[:max_topics],
        "mentioned_terms": mentioned,
        "comments_analyzed": len(comments),
        "requests_found": len(requests),
        "backend": embeddings.backend_name(),
    }
