package dev.iamkavindu.streamapp.lambda.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.TopicConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Idempotent Floci bootstrap for transcode-lambda integration tests.
 */
public final class LambdaTestAwsBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LambdaTestAwsBootstrap.class);

    public static final String UPLOAD_BUCKET = "streamapp-uploads";
    public static final String STREAM_BUCKET = "streamapp-streams";
    public static final String UPLOAD_TOPIC = "video-upload-events";
    public static final String UPLOAD_LAMBDA_QUEUE = "video-processing-lambda";
    public static final String TRANSCODE_COMPLETE_QUEUE = "video-transcode-complete-backend";
    public static final String ACCOUNT_ID = "000000000000";

    private final S3Client s3Client;
    private final SqsClient sqsClient;
    private final SnsClient snsClient;

    public LambdaTestAwsBootstrap(S3Client s3Client, SqsClient sqsClient, SnsClient snsClient) {
        this.s3Client = s3Client;
        this.sqsClient = sqsClient;
        this.snsClient = snsClient;
    }

    public void bootstrap() {
        createBucketIfMissing(UPLOAD_BUCKET);
        createBucketIfMissing(STREAM_BUCKET);

        var topicArn = ensureTopic();
        ensureQueue(UPLOAD_LAMBDA_QUEUE);
        ensureQueue(TRANSCODE_COMPLETE_QUEUE);

        ensureTopicPolicy(topicArn);
        ensureSnsQueuePolicy(UPLOAD_LAMBDA_QUEUE, topicArn);
        ensureTranscodeCompleteQueuePolicy(TRANSCODE_COMPLETE_QUEUE);
        subscribeQueueToTopic(UPLOAD_LAMBDA_QUEUE, topicArn);
        ensureBucketNotification(topicArn);

        log.info("Lambda test AWS bootstrap complete");
    }

    public String queueUrl(String queueName) {
        return sqsClient.getQueueUrl(req -> req.queueName(queueName)).queueUrl();
    }

    private void createBucketIfMissing(String bucketName) {
        try {
            s3Client.headBucket(req -> req.bucket(bucketName));
        } catch (Exception e) {
            s3Client.createBucket(req -> req.bucket(bucketName));
        }
    }

    private String ensureTopic() {
        return snsClient.createTopic(req -> req.name(UPLOAD_TOPIC)).topicArn();
    }

    private void ensureQueue(String queueName) {
        sqsClient.createQueue(req -> req.queueName(queueName));
    }

    private String queueArn(String queueName) {
        var queueUrl = queueUrl(queueName);
        return sqsClient
                .getQueueAttributes(req -> req.queueUrl(queueUrl).attributeNamesWithStrings("QueueArn"))
                .attributes()
                .get("QueueArn");
    }

    private void ensureTopicPolicy(String topicArn) {
        var bucketArn = "arn:aws:s3:::" + UPLOAD_BUCKET;
        var policy =
                """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Sid": "AllowS3UploadEvents",
                    "Effect": "Allow",
                    "Principal": { "Service": "s3.amazonaws.com" },
                    "Action": "SNS:Publish",
                    "Resource": "%s",
                    "Condition": {
                      "ArnLike": { "aws:SourceArn": "%s" },
                      "StringEquals": { "aws:SourceAccount": "%s" }
                    }
                  }]
                }
                """
                        .formatted(topicArn, bucketArn, ACCOUNT_ID);
        snsClient.setTopicAttributes(req -> req.topicArn(topicArn).attributeName("Policy").attributeValue(policy));
    }

    private void ensureSnsQueuePolicy(String queueName, String topicArn) {
        var queueArn = queueArn(queueName);
        var policy =
                """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Sid": "AllowSnsFanOut",
                    "Effect": "Allow",
                    "Principal": { "Service": "sns.amazonaws.com" },
                    "Action": "sqs:SendMessage",
                    "Resource": "%s",
                    "Condition": { "ArnEquals": { "aws:SourceArn": "%s" } }
                  }]
                }
                """
                        .formatted(queueArn, topicArn);
        setQueuePolicy(queueName, policy);
    }

    private void ensureTranscodeCompleteQueuePolicy(String queueName) {
        var queueArn = queueArn(queueName);
        var policy =
                """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Sid": "AllowLambdaCompletion",
                    "Effect": "Allow",
                    "Principal": { "Service": "lambda.amazonaws.com" },
                    "Action": "sqs:SendMessage",
                    "Resource": "%s",
                    "Condition": { "StringEquals": { "aws:SourceAccount": "%s" } }
                  }]
                }
                """
                        .formatted(queueArn, ACCOUNT_ID);
        setQueuePolicy(queueName, policy);
    }

    private void setQueuePolicy(String queueName, String policy) {
        var queueUrl = queueUrl(queueName);
        sqsClient.setQueueAttributes(req -> req.queueUrl(queueUrl)
                .attributes(java.util.Map.of(QueueAttributeName.POLICY, policy)));
    }

    private void subscribeQueueToTopic(String queueName, String topicArn) {
        var queueArn = queueArn(queueName);
        try {
            snsClient.subscribe(SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(java.util.Map.of("RawMessageDelivery", "false"))
                    .build());
        } catch (Exception e) {
            log.debug("SNS subscription may already exist: {}", e.getMessage());
        }
    }

    private void ensureBucketNotification(String topicArn) {
        var notification = NotificationConfiguration.builder()
                .topicConfigurations(TopicConfiguration.builder()
                        .id("upload-complete-sns")
                        .topicArn(topicArn)
                        .eventsWithStrings("s3:ObjectCreated:*")
                        .build())
                .build();
        s3Client.putBucketNotificationConfiguration(req -> req
                .bucket(UPLOAD_BUCKET)
                .notificationConfiguration(notification));
    }
}
