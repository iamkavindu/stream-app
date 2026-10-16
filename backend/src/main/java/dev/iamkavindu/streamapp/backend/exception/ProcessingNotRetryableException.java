package dev.iamkavindu.streamapp.backend.exception;

public class ProcessingNotRetryableException extends RuntimeException {
    public ProcessingNotRetryableException(String detail) {
        super(detail);
    }
}
