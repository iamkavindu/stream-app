package dev.iamkavindu.streamapp.backend.support;

import dev.iamkavindu.streamapp.backend.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import({TestcontainersConfiguration.class, FlociMessagingInitializer.class})
public @interface IntegrationTest {
}
