package com.yci.exception;

public class MlServiceException extends RuntimeException {
    public MlServiceException(String message) { super(message); }
    public MlServiceException(String message, Throwable cause) { super(message, cause); }
}
