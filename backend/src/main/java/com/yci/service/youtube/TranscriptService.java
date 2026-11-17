package com.yci.service.youtube;

import com.yci.service.ml.MlClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Reuse the caption service for both ingestion and on-demand summaries. */
@Service
public class TranscriptService {
    private static final Logger log = LoggerFactory.getLogger(TranscriptService.class);
    private final MlClient ml;

    public TranscriptService(MlClient ml) {
        this.ml = ml;
    }

    public String fetch(String videoId) {
        try {
            return ml.transcripts(List.of(videoId), "en").videos().stream()
                    .filter(v -> videoId.equals(v.video_id()) && "available".equals(v.status()))
                    .map(MlClient.TranscriptVideo::text)
                    .filter(text -> text != null && !text.isBlank())
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("Caption retrieval failed for {} ({})", videoId, e.getClass().getSimpleName());
            return null;
        }
    }
}
