"""Regression checks for the existing audience-demand pipeline."""
from app.demand import analyze_demand


def test_removing_blank_comments_preserves_like_count_alignment():
    result = analyze_demand(["", "Please make a video on Kafka consumer groups"], [100000, 0])
    assert result["comments_analyzed"] == 1
    assert result["requests_found"] == 1
    requested = [t for t in result["demand_topics"] if t["source"] == "requests"]
    assert requested[0]["demand_score"] == 3.0
