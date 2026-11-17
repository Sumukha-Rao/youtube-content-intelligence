"""Caption tests never contact YouTube and use the library's real exception types."""
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from requests.exceptions import Timeout
from youtube_transcript_api import (NoTranscriptFound, TranscriptsDisabled,
                                    VideoUnavailable, RequestBlocked, IpBlocked,
                                    AgeRestricted, VideoUnplayable)

from app.transcripts import fetch_transcript, router

VIDEO = "abcdefghijk"


def fetched(text="Actual caption content", language="en"):
    return SimpleNamespace(to_raw_data=lambda: [{"text": text}], language_code=language)


def test_preferred_automatic_language_before_manual_english():
    api = Mock()
    api.list.return_value.find_transcript.return_value.fetch.return_value = fetched(language="hi")
    result = fetch_transcript(api, VIDEO, "hi")
    assert result["status"] == "available"
    assert result["language"] == "hi"
    api.list.return_value.find_transcript.assert_called_once_with(["hi"])


def test_english_fallback_when_preferred_language_unavailable():
    api = Mock()
    available = api.list.return_value
    available.find_transcript.side_effect = [NoTranscriptFound(VIDEO, ["hi"], ""),
                                             SimpleNamespace(fetch=lambda: fetched())]
    result = fetch_transcript(api, VIDEO, "hi")
    assert result["status"] == "available"
    assert [call.args[0] for call in available.find_transcript.call_args_list] == [["hi"], ["en"]]


def test_regional_language_falls_back_to_base_then_english():
    api = Mock()
    api.list.return_value.find_transcript.side_effect = [NoTranscriptFound(VIDEO, [], ""),
        SimpleNamespace(fetch=lambda: fetched(language="pt"))]
    assert fetch_transcript(api, VIDEO, "pt-BR")["language"] == "pt"


@pytest.mark.parametrize("error", [TranscriptsDisabled(VIDEO), VideoUnavailable(VIDEO),
    AgeRestricted(VIDEO), VideoUnplayable(VIDEO, "private", []), NoTranscriptFound(VIDEO, [], "")])
def test_absent_or_inaccessible_captions_are_unavailable(error):
    api = Mock()
    api.list.side_effect = error
    assert fetch_transcript(api, VIDEO, "en")["status"] == "unavailable"


@pytest.mark.parametrize("error", [RequestBlocked(VIDEO), IpBlocked(VIDEO), Timeout(), ValueError("bad response")])
def test_blocked_network_and_parser_errors_are_retryable(error):
    api = Mock()
    api.list.side_effect = error
    result = fetch_transcript(api, VIDEO, "en")
    assert result["status"] == "error"
    assert "No transcript available" not in result["message"]


def test_long_subtitles_limited():
    api = Mock()
    api.list.return_value.find_transcript.return_value.fetch.return_value = fetched("x" * 100000)
    assert len(fetch_transcript(api, VIDEO, "en")["text"]) == 16000


def test_endpoint_mixed_success_and_failure_and_deduplication():
    app = FastAPI()
    app.include_router(router)
    with patch("app.transcripts.YouTubeTranscriptApi") as factory:
        def fail(video_id):
            if video_id == VIDEO:
                raise TranscriptsDisabled(video_id)
            raise RequestBlocked(video_id)
        factory.return_value.list.side_effect = fail
        result = TestClient(app).post("/ml/transcripts", json={"video_ids": [VIDEO, VIDEO, "12345678901"]})
        assert result.status_code == 200
        assert [v["status"] for v in result.json()["videos"]] == ["unavailable", "error"]


def test_endpoint_rejects_urls_and_unbounded_batches():
    app = FastAPI()
    app.include_router(router)
    client = TestClient(app)
    assert client.post("/ml/transcripts", json={"video_ids": ["https://youtube.com/watch?v=bad"]}).status_code == 422
    assert client.post("/ml/transcripts", json={"video_ids": [VIDEO] * 11}).status_code == 422

@pytest.mark.parametrize("preferred,expected", [("hi", "hi"), ("fr", "en")])
def test_real_library_selector_supports_manual_and_generated_tracks(preferred, expected):
    from youtube_transcript_api import TranscriptList
    # Exercise the actual pinned library selector, with no network calls.
    manual = SimpleNamespace(fetch=lambda: fetched("Manual English captions", "en"))
    generated = SimpleNamespace(fetch=lambda: fetched("Generated Hindi captions", "hi"))
    api = SimpleNamespace(list=lambda _: TranscriptList(VIDEO, {"en": manual}, {"hi": generated}, []))
    result = fetch_transcript(api, VIDEO, preferred)
    assert result["status"] == "available"
    assert result["language"] == expected


def test_english_regional_captions_are_available_to_english_users():
    from youtube_transcript_api import TranscriptList
    track = SimpleNamespace(language_code="en-GB", fetch=lambda: fetched("British English captions", "en-GB"))
    api = SimpleNamespace(list=lambda _: TranscriptList(VIDEO, {"en-GB": track}, {}, []))
    assert fetch_transcript(api, VIDEO, "en")["status"] == "available"


def test_empty_caption_response_is_not_reported_as_nonexistent_subtitles():
    api = Mock()
    api.list.return_value.find_transcript.return_value.fetch.return_value = fetched("   ")
    assert fetch_transcript(api, VIDEO, "en")["status"] == "error"


def test_native_language_captions_work_when_english_is_absent():
    from youtube_transcript_api import TranscriptList
    track = SimpleNamespace(language_code="ja", fetch=lambda: fetched("Native captions", "ja"))
    api = SimpleNamespace(list=lambda _: TranscriptList(VIDEO, {}, {"ja": track}, []))
    result = fetch_transcript(api, VIDEO, "en")
    assert result["status"] == "available"
    assert result["language"] == "ja"


def test_failed_manual_track_falls_back_to_generated_track():
    from youtube_transcript_api import TranscriptList
    broken = SimpleNamespace(language_code="en", fetch=Mock(side_effect=ValueError("empty track")))
    generated = SimpleNamespace(language_code="en", fetch=lambda: fetched("Generated captions"))
    api = SimpleNamespace(list=lambda _: TranscriptList(VIDEO, {"en": broken}, {"en": generated}, []))
    assert fetch_transcript(api, VIDEO, "en")["text"] == "Generated captions"


def test_language_and_caption_whitespace_are_normalized():
    api = Mock()
    api.list.return_value.find_transcript.return_value.fetch.return_value = fetched("Hello\n  world", "pt-BR")
    assert fetch_transcript(api, VIDEO, " PT_br ")["text"] == "Hello world"
    assert api.list.return_value.find_transcript.call_args_list[0].args[0] == ["pt-br"]


def test_normalized_region_prefers_exact_track_over_other_regions():
    from youtube_transcript_api import TranscriptList
    portugal = SimpleNamespace(language_code="pt-PT", fetch=lambda: fetched("Portugal", "pt-PT"))
    brazil = SimpleNamespace(language_code="pt-BR", fetch=lambda: fetched("Brazil", "pt-BR"))
    api = SimpleNamespace(list=lambda _: TranscriptList(VIDEO, {"pt-PT": portugal, "pt-BR": brazil}, {}, []))
    assert fetch_transcript(api, VIDEO, " PT_br ")["language"] == "pt-BR"
