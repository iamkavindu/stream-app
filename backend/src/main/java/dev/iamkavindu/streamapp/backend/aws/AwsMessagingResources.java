package dev.iamkavindu.streamapp.backend.aws;

/**
 * AWS messaging resource names provisioned by {@code docker/infra/aws/init-aws-resources.sh}.
 */
public final class AwsMessagingResources {

    public static final String UPLOAD_TOPIC = "video-upload-events";

    /** Receives upload-complete events from SNS; consumed by the backend for status updates. */
    public static final String UPLOAD_BACKEND_QUEUE = "video-processing-backend";

    /** Receives upload-complete events from SNS; consumed by the transcode Lambda. */
    public static final String UPLOAD_LAMBDA_QUEUE = "video-processing-lambda";

    /** Receives transcode-complete messages from Lambda; consumed by the backend. */
    public static final String TRANSCODE_COMPLETE_QUEUE = "video-transcode-complete-backend";

    private AwsMessagingResources() {}
}
