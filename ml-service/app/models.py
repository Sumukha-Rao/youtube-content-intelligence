"""
Classical ML models for sentiment and intent.

  Sentiment: TF-IDF -> Logistic Regression -> POSITIVE / NEGATIVE / NEUTRAL
  Intent:    TF-IDF -> Linear SVM (calibrated for scores) -> 7 intent classes

Models are trained from the seed CSVs in ../data and persisted to MODEL_DIR with
joblib. If a model file is missing, it is trained on demand. Retraining is exposed
via train.py and the /ml/train endpoint.
"""
import os
from typing import Dict, List

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.svm import LinearSVC

from .preprocessing import clean

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(BASE_DIR, "data")
MODEL_DIR = os.environ.get("MODEL_DIR", os.path.join(BASE_DIR, "models"))
os.makedirs(MODEL_DIR, exist_ok=True)

SENTIMENT_PATH = os.path.join(MODEL_DIR, "sentiment.joblib")
INTENT_PATH = os.path.join(MODEL_DIR, "intent.joblib")

SENTIMENT_LABELS = ["POSITIVE", "NEGATIVE", "NEUTRAL"]
INTENT_LABELS = ["CONTENT_REQUEST", "QUESTION", "PRAISE", "CRITICISM",
                 "SUGGESTION", "COMPLAINT", "OTHER"]

_sentiment_model = None
_intent_model = None


def _load_csv(name: str) -> pd.DataFrame:
    df = pd.read_csv(os.path.join(DATA_DIR, name))
    df["text"] = df["text"].astype(str).map(lambda s: clean(s))
    df = df[df["text"].str.len() > 0]
    return df


def train_sentiment() -> Dict:
    df = _load_csv("sentiment_training.csv")
    pipe = Pipeline([
        ("tfidf", TfidfVectorizer(ngram_range=(1, 2), min_df=1, sublinear_tf=True)),
        ("clf", LogisticRegression(max_iter=1000, C=4.0, class_weight="balanced")),
    ])
    pipe.fit(df["text"], df["label"])
    joblib.dump(pipe, SENTIMENT_PATH)
    global _sentiment_model
    _sentiment_model = pipe
    return {"model": "sentiment", "samples": int(len(df)),
            "classes": list(pipe.named_steps["clf"].classes_)}


def train_intent() -> Dict:
    df = _load_csv("intent_training.csv")
    # LinearSVC gives no probabilities; calibrate to expose confidence scores.
    base = Pipeline([
        ("tfidf", TfidfVectorizer(ngram_range=(1, 2), min_df=1, sublinear_tf=True)),
        ("svm", CalibratedClassifierCV(LinearSVC(C=1.0, class_weight="balanced"), cv=3)),
    ])
    base.fit(df["text"], df["label"])
    joblib.dump(base, INTENT_PATH)
    global _intent_model
    _intent_model = base
    return {"model": "intent", "samples": int(len(df)),
            "classes": list(base.named_steps["svm"].classes_)}


def _get_sentiment():
    global _sentiment_model
    if _sentiment_model is None:
        if os.path.exists(SENTIMENT_PATH):
            _sentiment_model = joblib.load(SENTIMENT_PATH)
        else:
            train_sentiment()
    return _sentiment_model


def _get_intent():
    global _intent_model
    if _intent_model is None:
        if os.path.exists(INTENT_PATH):
            _intent_model = joblib.load(INTENT_PATH)
        else:
            train_intent()
    return _intent_model


def predict_sentiment(texts: List[str]) -> List[Dict]:
    model = _get_sentiment()
    cleaned = [clean(t) for t in texts]
    proba = model.predict_proba(cleaned)
    classes = model.named_steps["clf"].classes_
    out = []
    for raw, row in zip(texts, proba):
        j = int(np.argmax(row))
        out.append({"text": raw, "sentiment": str(classes[j]), "score": round(float(row[j]), 4)})
    return out


def predict_intent(texts: List[str]) -> List[Dict]:
    model = _get_intent()
    cleaned = [clean(t) for t in texts]
    proba = model.predict_proba(cleaned)
    classes = model.named_steps["svm"].classes_
    out = []
    for raw, row in zip(texts, proba):
        j = int(np.argmax(row))
        out.append({"text": raw, "intent": str(classes[j]), "score": round(float(row[j]), 4)})
    return out


def train_all() -> Dict:
    return {"sentiment": train_sentiment(), "intent": train_intent()}
