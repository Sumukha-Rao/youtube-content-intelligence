package com.yci.entity;

/** How much history to pull per channel when ingesting. */
public enum RetrievalMode {
    /** Take the most recent N videos. */
    LAST_N_VIDEOS,
    /** Take every video published in the last N days. */
    LAST_N_DAYS
}
