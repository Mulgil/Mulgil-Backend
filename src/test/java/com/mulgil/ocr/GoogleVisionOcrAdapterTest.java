package com.mulgil.ocr;

import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Block;
import com.google.cloud.vision.v1.BoundingPoly;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.Page;
import com.google.cloud.vision.v1.Paragraph;
import com.google.cloud.vision.v1.Symbol;
import com.google.cloud.vision.v1.TextAnnotation;
import com.google.cloud.vision.v1.Vertex;
import com.google.cloud.vision.v1.Word;
import com.google.rpc.Code;
import com.google.rpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.mulgil.common.config.MulgilProperties;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleVisionOcrAdapterTest {
    @Test
    void extractsDocumentTextBlocks_whenVisionReturnsMixedBlocks() throws Exception {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(org.mockito.ArgumentMatchers.<AnnotateImageRequest>anyList()))
                .thenReturn(response(block("recognized text", 0.75f,
                                List.of(vertex(-20, 10), vertex(120, 10), vertex(120, 60), vertex(-20, 60))),
                        block("   ", 0.8f, List.of(vertex(0, 0), vertex(10, 0), vertex(10, 10), vertex(0, 10))),
                        block("missing box", 0.9f, List.of())));
        GoogleVisionOcrAdapter adapter = new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");

        VisionOcrPort.OcrResult result = adapter.extract(png(100, 50));

        ArgumentCaptor<List<AnnotateImageRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(client).batchAnnotateImages(requests.capture());
        assertThat(requests.getValue()).singleElement().satisfies(request ->
                assertThat(request.getFeatures(0).getType()).isEqualTo(Feature.Type.DOCUMENT_TEXT_DETECTION));
        assertThat(result.provider()).isEqualTo("google-vision");
        assertThat(result.model()).isEqualTo("DOCUMENT_TEXT_DETECTION");
        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.text()).isEqualTo("recognized text");
            assertThat(block.confidence()).isEqualTo(0.75);
            assertThat(block.box()).isEqualTo(new VisionOcrPort.NormalizedBox(0, 0.2, 1, 0.8));
        });
    }

    @Test
    void rejectsMalformedImage_withoutCallingVision() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        GoogleVisionOcrAdapter adapter = new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");

        assertThatThrownBy(() -> adapter.extract(new byte[]{1, 2, 3}))
                .isInstanceOfSatisfying(OcrProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("OCR_INVALID_IMAGE");
                    assertThat(exception.retryable()).isFalse();
                });
        verifyNoInteractions(client);
    }

    @Test
    void mapsProviderFailure_withoutExposingProviderMessage() throws Exception {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(org.mockito.ArgumentMatchers.<AnnotateImageRequest>anyList()))
                .thenReturn(BatchAnnotateImagesResponse.newBuilder().addResponses(
                        AnnotateImageResponse.newBuilder().setError(Status.newBuilder()
                                .setCode(Code.UNAVAILABLE_VALUE).setMessage("untrusted-provider-content")).build()).build());
        GoogleVisionOcrAdapter adapter = new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");

        assertThatThrownBy(() -> adapter.extract(png(10, 10)))
                .isInstanceOfSatisfying(OcrProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("untrusted-provider-content");
                });
    }

    @Test
    void mapsResourceExhausted_toRetryableProviderRateLimit() throws Exception {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(org.mockito.ArgumentMatchers.<AnnotateImageRequest>anyList()))
                .thenReturn(BatchAnnotateImagesResponse.newBuilder().addResponses(
                        AnnotateImageResponse.newBuilder().setError(Status.newBuilder()
                                .setCode(Code.RESOURCE_EXHAUSTED_VALUE).build()).build()).build());
        GoogleVisionOcrAdapter adapter = new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");

        assertThatThrownBy(() -> adapter.extract(png(10, 10)))
                .isInstanceOfSatisfying(OcrProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_RATE_LIMIT");
                    assertThat(exception.retryable()).isTrue();
                });
    }

    @Test
    void rejectsMalformedVisionBatch_asTerminalInvalidResponse() throws Exception {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(org.mockito.ArgumentMatchers.<AnnotateImageRequest>anyList()))
                .thenReturn(BatchAnnotateImagesResponse.getDefaultInstance());
        GoogleVisionOcrAdapter adapter = new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");

        assertThatThrownBy(() -> adapter.extract(png(10, 10)))
                .isInstanceOfSatisfying(OcrProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("OCR_INVALID_RESPONSE");
                    assertThat(exception.retryable()).isFalse();
                });
    }

    @Test
    void mapsPermissionDenied_toTerminalProviderAuthenticationFailed() throws Exception {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(org.mockito.ArgumentMatchers.<AnnotateImageRequest>anyList()))
                .thenReturn(BatchAnnotateImagesResponse.newBuilder().addResponses(
                        AnnotateImageResponse.newBuilder().setError(Status.newBuilder()
                                .setCode(Code.PERMISSION_DENIED_VALUE).build()).build()).build());
        GoogleVisionOcrAdapter adapter = new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");

        assertThatThrownBy(() -> adapter.extract(png(10, 10)))
                .isInstanceOfSatisfying(OcrProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_AUTHENTICATION_FAILED");
                    assertThat(exception.retryable()).isFalse();
                });
    }

    @Test
    void returnsSuccessfulEmptyResult_whenVisionAnnotationIsEmpty() throws Exception {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(org.mockito.ArgumentMatchers.<AnnotateImageRequest>anyList()))
                .thenReturn(BatchAnnotateImagesResponse.newBuilder()
                        .addResponses(AnnotateImageResponse.getDefaultInstance()).build());
        GoogleVisionOcrAdapter adapter = new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");

        VisionOcrPort.OcrResult result = adapter.extract(png(10, 10));

        assertThat(result.blocks()).isEmpty();
        assertThat(result.provider()).isEqualTo("google-vision");
        assertThat(result.model()).isEqualTo("DOCUMENT_TEXT_DETECTION");
    }

    @Test
    void applicationContextFailsToStart_whenConfiguredFeatureUnsupported() {
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.vision()).thenReturn(new MulgilProperties.Vision("TEXT_DETECTION"));

        new ApplicationContextRunner()
                .withBean(MulgilProperties.class, () -> properties)
                .withBean(GoogleVisionOcrAdapter.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("mulgil.vision.feature must be DOCUMENT_TEXT_DETECTION.");
                });
    }

    private static BatchAnnotateImagesResponse response(Block... blocks) {
        Page page = Page.newBuilder().addAllBlocks(List.of(blocks)).build();
        return BatchAnnotateImagesResponse.newBuilder().addResponses(AnnotateImageResponse.newBuilder()
                .setFullTextAnnotation(TextAnnotation.newBuilder().addPages(page).build()).build()).build();
    }

    private static Block block(String text, float confidence, List<Vertex> vertices) {
        Word.Builder word = Word.newBuilder();
        text.codePoints().forEach(value -> word.addSymbols(Symbol.newBuilder()
                .setText(new String(Character.toChars(value))).build()));
        Block.Builder block = Block.newBuilder().setConfidence(confidence)
                .addParagraphs(Paragraph.newBuilder().addWords(word).build());
        if (!vertices.isEmpty()) block.setBoundingBox(BoundingPoly.newBuilder().addAllVertices(vertices).build());
        return block.build();
    }

    private static Vertex vertex(int x, int y) {
        return Vertex.newBuilder().setX(x).setY(y).build();
    }

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
