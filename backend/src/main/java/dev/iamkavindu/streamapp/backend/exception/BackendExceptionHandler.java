package dev.iamkavindu.streamapp.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice(basePackages = "dev.iamkavindu.streamapp.backend")
public class BackendExceptionHandler {

    @ExceptionHandler(DuplicateVideoUploadException.class)
    public ProblemDetail duplicateVideoUpload(DuplicateVideoUploadException e) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Duplicate Video Upload");
        pd.setType(ProblemTypes.of("duplicate-video-upload"));
        return pd;
    }

    @ExceptionHandler(VideoNotFoundException.class)
    public ProblemDetail videoNotFound(VideoNotFoundException e) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Video Not Found");
        pd.setType(ProblemTypes.of("video-not-found"));
        return pd;
    }

    @ExceptionHandler(VideoNotReadyException.class)
    public ProblemDetail videoNotReady(VideoNotReadyException e) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Video Not Ready");
        pd.setType(ProblemTypes.of("video-not-ready"));
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailed(MethodArgumentNotValidException e) {
        var detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Validation Failed");
        pd.setType(ProblemTypes.of("validation-failed"));
        return pd;
    }
}
