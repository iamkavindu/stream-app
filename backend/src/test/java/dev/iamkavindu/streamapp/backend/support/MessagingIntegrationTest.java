package dev.iamkavindu.streamapp.backend.support;

import dev.iamkavindu.streamapp.backend.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Integration test with SQS listeners enabled for async messaging assertions.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import({TestcontainersConfiguration.class, FlociMessagingInitializer.class})
@TestPropertySource(properties = "spring.cloud.aws.sqs.listener.auto-startup=true")
public @interface MessagingIntegrationTest {
}
