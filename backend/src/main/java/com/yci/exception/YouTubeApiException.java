package com.yci.exception;

/** Wraps all YouTube Data API failures (quota, not found, comments disabled, ...). */
public class YouTubeApiException extends RuntimeException {
    public YouTubeApiException(String message) { super(message); }
    public YouTubeApiException(String message, Throwable cause) { super(message, cause); }
}
