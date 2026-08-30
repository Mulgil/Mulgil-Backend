package com.mulgil.stt;

import com.google.cloud.speech.v2.AutoDetectDecodingConfig;
import com.google.cloud.speech.v2.BatchRecognizeFileMetadata;
import com.google.cloud.speech.v2.BatchRecognizeFileResult;
import com.google.cloud.speech.v2.BatchRecognizeRequest;
import com.google.cloud.speech.v2.BatchRecognizeResponse;
import com.google.cloud.speech.v2.InlineOutputConfig;
import com.google.cloud.speech.v2.RecognitionConfig;
import com.google.cloud.speech.v2.RecognitionFeatures;
import com.google.cloud.speech.v2.RecognitionOutputConfig;
import com.google.cloud.speech.v2.SpeechClient;
import com.google.cloud.speech.v2.SpeechRecognitionAlternative;
import com.google.cloud.speech.v2.SpeechRecognitionResult;
import com.google.cloud.speech.v2.SpeechSettings;
import com.google.cloud.speech.v2.WordInfo;
import com.mulgil.common.config.MulgilProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Profile("!test & !smoke")
final class GoogleChirpSpeechToTextAdapter implements SpeechToTextPort {
    private final MulgilProperties properties;

    GoogleChirpSpeechToTextAdapter(MulgilProperties properties) {
        this.properties = properties;
    }

    @Override
    public Transcript transcribe(URI objectUri, Duration offset) {
        try (SpeechClient client = SpeechClient.create(SpeechSettings.newBuilder()
                .setEndpoint(properties.speech().apiEndpoint()).build())) {
            RecognitionConfig config = RecognitionConfig.newBuilder()
                    .setAutoDecodingConfig(AutoDetectDecodingConfig.newBuilder().build())
                    .setModel(properties.speech().model()).addLanguageCodes("ko-KR")
                    .setFeatures(RecognitionFeatures.newBuilder().setEnableWordTimeOffsets(true)
                            .setEnableWordConfidence(true).setEnableAutomaticPunctuation(true).build())
                    .build();
            String recognizer = "projects/%s/locations/%s/recognizers/_".formatted(
                    properties.google().cloudProject(), properties.speech().location());
            BatchRecognizeRequest request = BatchRecognizeRequest.newBuilder().setRecognizer(recognizer)
                    .setConfig(config).addFiles(BatchRecognizeFileMetadata.newBuilder()
                            .setUri(objectUri.toString()).build())
                    .setRecognitionOutputConfig(RecognitionOutputConfig.newBuilder()
                            .setInlineResponseConfig(InlineOutputConfig.newBuilder().build()).build())
                    .build();
            BatchRecognizeResponse response = client.batchRecognizeAsync(request).get(
                    properties.jobs().providerTimeoutSeconds(), TimeUnit.SECONDS);
            BatchRecognizeFileResult file = response.getResultsMap().get(objectUri.toString());
            if (file == null || file.hasError()) throw new IllegalStateException("Chirp returned no transcript.");
            List<Segment> segments = new ArrayList<>();
            long previousEnd = 0;
            for (SpeechRecognitionResult result : file.getTranscript().getResultsList()) {
                if (result.getAlternativesCount() == 0) continue;
                SpeechRecognitionAlternative alternative = result.getAlternatives(0);
                String text = alternative.getTranscript().strip();
                if (text.isEmpty()) continue;
                long start = previousEnd;
                long end = millis(result.getResultEndOffset());
                if (alternative.getWordsCount() > 0) {
                    WordInfo first = alternative.getWords(0);
                    WordInfo last = alternative.getWords(alternative.getWordsCount() - 1);
                    start = millis(first.getStartOffset());
                    end = millis(last.getEndOffset());
                }
                if (end <= start) continue;
                Double confidence = alternative.getConfidence() > 0 ? (double) alternative.getConfidence() : null;
                segments.add(new Segment(start, end, text, confidence));
                previousEnd = end;
            }
            return new Transcript(segments, "google-chirp", properties.speech().model());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create regional Chirp client.", exception);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Regional Chirp transcription failed.", exception);
        }
    }

    private static long millis(com.google.protobuf.Duration value) {
        return value.getSeconds() * 1_000 + value.getNanos() / 1_000_000;
    }
}
