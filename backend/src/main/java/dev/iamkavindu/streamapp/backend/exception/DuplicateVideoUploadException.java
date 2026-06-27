package dev.iamkavindu.streamapp.backend.exception;

public class DuplicateVideoUploadException extends RuntimeException {
    public DuplicateVideoUploadException(String fileName) {
        super("Video is already uploaded: " + fileName);
    }
}
