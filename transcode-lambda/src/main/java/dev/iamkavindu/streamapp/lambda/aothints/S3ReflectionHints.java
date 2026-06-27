package dev.iamkavindu.streamapp.lambda.aothints;

import dev.iamkavindu.streamapp.lambda.model.VideoStatus;
import dev.iamkavindu.streamapp.lambda.model.VideoStatusUpdateRecord;
import jakarta.annotation.Nullable;
import org.jspecify.annotations.NonNull;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class S3ReflectionHints implements RuntimeHintsRegistrar {

    private static final String AWS_LAMBDA_JODA_TZ_DATA =
            "com/amazonaws/lambda/thirdparty/org/joda/time/tz/data/**";

    @Override
    public void registerHints(@NonNull RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints.resources().registerPattern(AWS_LAMBDA_JODA_TZ_DATA);

        hints.reflection()
                .registerType(
                        org.springframework.cloud.function.adapter.aws.CustomRuntimeEventLoop.class,
                        builder -> builder.withMembers(
                                MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
        try {
            hints.reflection()
                    .registerType(
                            Class.forName("org.springframework.cloud.function.adapter.aws.AWSCustomRuntime"),
                            builder -> builder.withMembers(
                                    MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
        } catch (ClassNotFoundException ignored) {
        }
        try {
            hints.reflection()
                    .registerType(
                            Class.forName(
                                    "org.springframework.cloud.function.adapter.aws.LambdaCustomRuntimeAutoConfiguration"),
                            builder -> builder.withMembers(
                                    MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
        } catch (ClassNotFoundException ignored) {
        }
        try {
            hints.reflection()
                    .registerType(
                            Class.forName("org.springframework.cloud.function.adapter.aws.CustomRuntimeInitializer"),
                            builder -> builder.withMembers(
                                    MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
        } catch (ClassNotFoundException ignored) {
        }

        Class<?>[] eventTypes = {
            VideoStatusUpdateRecord.class,
            VideoStatus.class
        };
        for (Class<?> type : eventTypes) {
            hints.reflection()
                    .registerType(
                            type,
                            builder -> builder.withMembers(
                                    MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS));
        }
    }
}
