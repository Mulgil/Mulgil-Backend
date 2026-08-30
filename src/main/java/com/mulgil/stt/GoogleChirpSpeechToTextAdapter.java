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
import com.google.api.gax.rpc.DeadlineExceededException;
import com.google.longrunning.Operation;
import com.google.longrunning.WaitOperationRequest;
import com.mulgil.common.config.MulgilProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Profile("!test & !smoke")
final class GoogleChirpSpeechToTextAdapter implements SpeechToTextPort {
    private final MulgilProperties properties;

    GoogleChirpSpeechToTextAdapter(MulgilProperties properties) {
        this.properties = properties;
    }

    @Override
    public String start(Input input) {
        try (SpeechClient client = SpeechClient.create(SpeechSettings.newBuilder()
                .setEndpoint(properties.speech().apiEndpoint()).build())) {
            BatchRecognizeRequest request = BatchRecognizeRequest.newBuilder()
                    .setRecognizer("projects/%s/locations/%s/recognizers/_".formatted(
                            properties.google().cloudProject(), properties.speech().location()))
                    .setConfig(config())
                    .setRecognitionOutputConfig(RecognitionOutputConfig.newBuilder()
                            .setInlineResponseConfig(InlineOutputConfig.newBuilder().build()).build())
                    .addFiles(BatchRecognizeFileMetadata.newBuilder()
                            .setUri(input.objectUri().toString()).build())
                    .build();
            return client.batchRecognizeAsync(request).getName();
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not start regional Chirp transcription.", exception);
        }
    }

    @Override
    public Optional<Transcript> await(String operationId, Input input, Duration pollTimeout) {
        try (SpeechClient client = SpeechClient.create(SpeechSettings.newBuilder()
                .setEndpoint(properties.speech().apiEndpoint()).build())) {
            com.google.protobuf.Duration timeout = com.google.protobuf.Duration.newBuilder()
                    .setSeconds(pollTimeout.toSeconds()).setNanos(pollTimeout.toNanosPart()).build();
            Operation operation = client.getOperationsClient().waitOperation(WaitOperationRequest.newBuilder()
                    .setName(operationId).setTimeout(timeout).build());
            if (!operation.getDone()) return Optional.empty();
            if (operation.hasError()) throw new TerminalOperationException(operation.getError().getMessage());
            BatchRecognizeResponse response = operation.getResponse().unpack(BatchRecognizeResponse.class);
            List<Segment> segments = new ArrayList<>();
            BatchRecognizeFileResult file = response.getResultsMap().get(input.objectUri().toString());
            if (file == null || file.hasError()) throw new TerminalOperationException(
                    file == null ? "Chirp returned no transcript." : file.getError().getMessage());
            appendSegments(segments, file, input.offset());
            return Optional.of(new Transcript(segments, "google-chirp", properties.speech().model()));
        } catch (TerminalOperationException exception) {
            throw exception;
        } catch (DeadlineExceededException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not poll regional Chirp transcription.", exception);
        }
    }

    private RecognitionConfig config() {
        return RecognitionConfig.newBuilder()
                .setAutoDecodingConfig(AutoDetectDecodingConfig.newBuilder().build())
                .setModel(properties.speech().model()).addLanguageCodes("ko-KR")
                .setFeatures(RecognitionFeatures.newBuilder().setEnableWordTimeOffsets(true)
                        .setEnableWordConfidence(true).setEnableAutomaticPunctuation(true).build())
                .build();
    }

    private static void appendSegments(List<Segment> segments, BatchRecognizeFileResult file, Duration offset) {
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
            segments.add(new Segment(offset.toMillis() + start, offset.toMillis() + end, text, confidence));
            previousEnd = end;
        }
    }

    private static long millis(com.google.protobuf.Duration value) {
        return value.getSeconds() * 1_000 + value.getNanos() / 1_000_000;
    }
}
