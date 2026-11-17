"""Retrieve existing captions only. No transcription, proxies, cookies or bypasses."""
import logging
import re
from concurrent.futures import ThreadPoolExecutor
from collections.abc import Iterable
from typing import List

from fastapi import APIRouter
from pydantic import BaseModel, Field
from requests import Session
from youtube_transcript_api import (YouTubeTranscriptApi, NoTranscriptFound,
                                    TranscriptsDisabled, VideoUnavailable,
                                    AgeRestricted, VideoUnplayable, RequestBlocked)

log = logging.getLogger(__name__)
router = APIRouter()


class TranscriptRequest(BaseModel):
    video_ids: List[str] = Field(max_length=10)
    language: str = Field(default="en", max_length=32)


class TimeoutSession(Session):
    def request(self, *args, **kwargs):
        kwargs.setdefault("timeout", (5, 15))
        return super().request(*args, **kwargs)


def fetch_transcript(api, video_id, language):
    language = (language or "en").strip().replace("_", "-").lower() or "en"
    languages = list(dict.fromkeys([language, language.split("-")[0], "en"]))
    try:
        # Select one language at a time: preferred automatic captions outrank
        # English manual captions. find_transcript supports both caption types.
        available = api.list(video_id)
        tracks = list(available) if isinstance(available, Iterable) else []
        def candidates():
            seen = set()
            for lang in languages:
                try:
                    track = available.find_transcript([lang])
                except NoTranscriptFound:
                    variants = [getattr(t, "language_code", "") for t in tracks
                                if getattr(t, "language_code", "").lower().split("-")[0] == lang.split("-")[0]]
                    if not variants:
                        continue
                    variants.sort(key=lambda code: code.lower() != lang)
                    track = available.find_transcript(variants)
                if id(track) not in seen:
                    seen.add(id(track))
                    yield track
            # The summarizer can use original captions in any source language.
            for track in tracks:
                if id(track) not in seen:
                    seen.add(id(track))
                    yield track

        last_error = None
        for track in candidates():
            try:
                fetched = track.fetch()
                text = re.sub(r"\s+", " ", " ".join(s["text"] for s in fetched.to_raw_data())).strip()
                if not text:
                    raise ValueError("YouTube returned an empty caption response")
            except RequestBlocked:
                raise
            except Exception as exc:
                last_error = exc
                continue
            return {"video_id": video_id, "status": "available",
                    "text": text[:16000], "language": fetched.language_code}
        if last_error is not None:
            raise last_error
        return {"video_id": video_id, "status": "unavailable"}
    except (NoTranscriptFound, TranscriptsDisabled, VideoUnavailable, AgeRestricted, VideoUnplayable):
        return {"video_id": video_id, "status": "unavailable"}
    except RequestBlocked:
        return {"video_id": video_id, "status": "error",
                "message": "YouTube blocked caption requests from this server. Captions may still be available on YouTube; retry later."}
    except Exception as exc:
        # Unknown/parser/network errors do not prove captions are absent.
        log.warning("Subtitle retrieval failed for %s (%s)", video_id, type(exc).__name__)
        return {"video_id": video_id, "status": "error",
                "message": "Subtitle retrieval failed temporarily. Please try again later."}


@router.post("/ml/transcripts")
def transcripts(req: TranscriptRequest):
    from fastapi import HTTPException
    if any(not re.fullmatch(r"[A-Za-z0-9_-]{11}", v) for v in req.video_ids):
        raise HTTPException(422, "Invalid YouTube video ID")
    def fetch(video_id):
        # Requests sessions are not shared between workers.
        with TimeoutSession() as session:
            return fetch_transcript(YouTubeTranscriptApi(http_client=session), video_id, req.language)

    with ThreadPoolExecutor(max_workers=3) as workers:
        return {"videos": list(workers.map(fetch, dict.fromkeys(req.video_ids)))}
