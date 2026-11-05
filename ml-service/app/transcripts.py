"""Retrieve existing captions only. No transcription, proxies, cookies or bypasses."""
import logging
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
    languages = list(dict.fromkeys([language, language.split("-")[0], "en"]))
    try:
        # Select one language at a time: preferred automatic captions outrank
        # English manual captions. find_transcript supports both caption types.
        available = api.list(video_id)
        for lang in languages:
            try:
                track = available.find_transcript([lang])
            except NoTranscriptFound:
                # An English caption can be labelled en-GB/en-US, likewise other
                # regional variants. Retain manual/generated selector behavior.
                variants = [getattr(t, "language_code", "") for t in available
                            if getattr(t, "language_code", "").split("-")[0] == lang.split("-")[0]] if isinstance(available, Iterable) else []
                if not variants:
                    continue
                track = available.find_transcript(variants)
            fetched = track.fetch()
            text = " ".join(s["text"] for s in fetched.to_raw_data()).strip()
            if not text:
                raise ValueError("YouTube returned an empty caption response")
            return {"video_id": video_id, "status": "available",
                    "text": text[:16000], "language": fetched.language_code}
        return {"video_id": video_id, "status": "unavailable"}
    except (NoTranscriptFound, TranscriptsDisabled, VideoUnavailable, AgeRestricted, VideoUnplayable):
        return {"video_id": video_id, "status": "unavailable"}
    except RequestBlocked:
        return {"video_id": video_id, "status": "error",
                "message": "YouTube blocked subtitle retrieval. Please try again later."}
    except Exception as exc:
        # Unknown/parser/network errors do not prove captions are absent.
        log.warning("Subtitle retrieval failed for %s (%s)", video_id, type(exc).__name__)
        return {"video_id": video_id, "status": "error",
                "message": "Subtitle retrieval failed temporarily. Please try again later."}


@router.post("/ml/transcripts")
def transcripts(req: TranscriptRequest):
    import re
    from fastapi import HTTPException
    if any(not re.fullmatch(r"[A-Za-z0-9_-]{11}", v) for v in req.video_ids):
        raise HTTPException(422, "Invalid YouTube video ID")
    with TimeoutSession() as session:
        api = YouTubeTranscriptApi(http_client=session)
        return {"videos": [fetch_transcript(api, v, req.language or "en")
                           for v in dict.fromkeys(req.video_ids)]}
