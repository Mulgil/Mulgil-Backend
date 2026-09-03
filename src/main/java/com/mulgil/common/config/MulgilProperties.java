package com.mulgil.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties("mulgil")
public record MulgilProperties(
        @Valid Jwt jwt,
        @Valid Google google,
        @Valid Gcs gcs,
        @Valid Vision vision,
        @Valid Speech speech,
        @Valid Vertex vertex,
        @Valid Fcm fcm,
        @Valid Ocr ocr,
        @Valid Jobs jobs,
        @Valid Uploads uploads,
        @Valid Demo demo,
        @Valid AiRates aiRates,
        @Valid Notifications notifications,
        @Valid Cors cors
) {
    public record Jwt(
            @NotBlank String secretBase64,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotBlank String keyId,
            @Min(1) long accessTtlSeconds,
            @Min(1) long refreshTtlDays
    ) {}

    public record Google(
            @NotBlank String oauthClientId,
            @NotBlank String cloudProject,
            @NotBlank String cloudLocation
    ) {}

    public record Gcs(@NotBlank String bucket, @Min(1) long signedUrlTtlSeconds) {}

    public record Vision(@NotBlank String feature) {}

    public record Speech(
            @NotBlank String location,
            @NotBlank String apiEndpoint,
            @NotBlank String model
    ) {}

    public record Vertex(
            @NotBlank String generationModel,
            @NotBlank String embeddingModel,
            @Min(1) @Max(20) int embeddingBatchSize
    ) {}

    public record Fcm(boolean enabled) {}

    public record Ocr(
            @Min(0) int minEmbeddedTextCharacters,
            @DecimalMin("0.0") @DecimalMax("1.0") double imageCoverageThreshold,
            @DecimalMin("0.0") @DecimalMax("1.0") double handwritingConfidenceThreshold
    ) {}

    public record Jobs(
            @Min(0) int maxRetry,
            @Min(1) long leaseSeconds,
            @Min(1) long providerTimeoutSeconds
    ) {}

    public record Uploads(
            @Min(1) long maxPdfBytes,
            @Min(1) int maxPdfPages,
            @Min(1) int maxPdfsPerSession,
            @Min(1) long maxAudioDurationSeconds,
            @Min(1) long sttSegmentDurationSeconds,
            @NotEmpty List<@NotBlank String> recordingMimeTypes,
            @NotBlank String ffmpegPath
    ) {
        public Uploads {
            recordingMimeTypes = List.copyOf(recordingMimeTypes);
        }
    }

    public record Demo(boolean cacheEnabled, @Min(1) int maxAiJobsPerDay) {}

    public record AiRates(
            @Min(0) long visionImageMicrousd,
            @Min(0) long speechSecondMicrousd,
            @Min(0) long embeddingCharacterMicrousd,
            @Min(0) long generationCharacterMicrousd
    ) {}

    public record Notifications(
            @Min(1) int postClassReminderHours,
            @NotEmpty List<@Min(1) Integer> examReminderDays
    ) {
        public Notifications {
            examReminderDays = List.copyOf(examReminderDays);
        }
    }

    public record Cors(
            @NotEmpty List<@NotBlank String> allowedOriginPatterns
    ) {
        public Cors {
            allowedOriginPatterns = List.copyOf(allowedOriginPatterns);
        }
    }
}
