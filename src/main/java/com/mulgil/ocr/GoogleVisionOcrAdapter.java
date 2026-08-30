package com.mulgil.ocr;

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Block;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.Paragraph;
import com.google.cloud.vision.v1.Vertex;
import com.google.cloud.vision.v1.Word;
import com.google.protobuf.ByteString;
import com.google.rpc.Code;
import com.mulgil.common.config.MulgilProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test & !smoke")
final class GoogleVisionOcrAdapter implements VisionOcrPort {
    private static final String FEATURE = "DOCUMENT_TEXT_DETECTION";
    private final ImageAnnotatorClient client;

    @Autowired
    GoogleVisionOcrAdapter(MulgilProperties properties) throws IOException {
        requireFeature(properties.vision().feature());
        this.client = ImageAnnotatorClient.create();
    }

    GoogleVisionOcrAdapter(ImageAnnotatorClient client, String feature) {
        requireFeature(feature);
        this.client = client;
    }

    @Override
    public OcrResult extract(byte[] image) {
        BufferedImage raster;
        try {
            raster = ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException exception) {
            throw invalidImage();
        }
        if (raster == null || raster.getWidth() <= 0 || raster.getHeight() <= 0) throw invalidImage();
        AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                .setImage(Image.newBuilder().setContent(ByteString.copyFrom(image)).build())
                .addFeatures(Feature.newBuilder().setType(Feature.Type.DOCUMENT_TEXT_DETECTION).build())
                .build();
        BatchAnnotateImagesResponse batch;
        try {
            batch = client.batchAnnotateImages(List.of(request));
        } catch (ApiException exception) {
            throw providerFailure(Code.valueOf(exception.getStatusCode().getCode().name()), exception.isRetryable());
        }
        if (batch.getResponsesCount() != 1) {
            throw new OcrProviderException("OCR_INVALID_RESPONSE", "Vision returned an invalid response.", false);
        }
        AnnotateImageResponse response = batch.getResponses(0);
        if (response.hasError()) {
            Code code = Code.forNumber(response.getError().getCode());
            throw providerFailure(code, retryable(code));
        }
        List<OcrBlock> blocks = new ArrayList<>();
        response.getFullTextAnnotation().getPagesList().forEach(page -> page.getBlocksList().forEach(block -> {
            String text = text(block).strip();
            NormalizedBox box = box(block, raster.getWidth(), raster.getHeight());
            if (!text.isEmpty() && box != null) {
                double confidence = Float.isFinite(block.getConfidence()) ? block.getConfidence() : 0;
                blocks.add(new OcrBlock(text, confidence, box));
            }
        }));
        return new OcrResult(blocks, "google-vision", FEATURE);
    }

    @PreDestroy
    void close() {
        client.close();
    }

    private static String text(Block block) {
        List<String> paragraphs = new ArrayList<>();
        for (Paragraph paragraph : block.getParagraphsList()) {
            List<String> words = new ArrayList<>();
            for (Word word : paragraph.getWordsList()) {
                String value = word.getSymbolsList().stream().map(symbol -> symbol.getText())
                        .reduce("", String::concat).strip();
                if (!value.isEmpty()) words.add(value);
            }
            if (!words.isEmpty()) paragraphs.add(String.join(" ", words));
        }
        return String.join("\n", paragraphs);
    }

    private static NormalizedBox box(Block block, int imageWidth, int imageHeight) {
        if (!block.hasBoundingBox() || block.getBoundingBox().getVerticesCount() < 2) return null;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Vertex vertex : block.getBoundingBox().getVerticesList()) {
            minX = Math.min(minX, vertex.getX());
            minY = Math.min(minY, vertex.getY());
            maxX = Math.max(maxX, vertex.getX());
            maxY = Math.max(maxY, vertex.getY());
        }
        double left = clamp((double) minX / imageWidth);
        double top = clamp((double) minY / imageHeight);
        double right = clamp((double) maxX / imageWidth);
        double bottom = clamp((double) maxY / imageHeight);
        if (right <= left || bottom <= top) return null;
        return new NormalizedBox(left, top, right - left, bottom - top);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static OcrProviderException invalidImage() {
        return new OcrProviderException("OCR_INVALID_IMAGE", "OCR image is invalid.", false);
    }

    private static void requireFeature(String feature) {
        if (!FEATURE.equals(feature)) {
            throw new IllegalStateException("mulgil.vision.feature must be DOCUMENT_TEXT_DETECTION.");
        }
    }

    private static OcrProviderException providerFailure(Code code, boolean retryable) {
        if (code == Code.INVALID_ARGUMENT) return invalidImage();
        if (code == Code.DEADLINE_EXCEEDED) {
            return new OcrProviderException("PROVIDER_TIMEOUT", "Vision request timed out.", true);
        }
        if (code == Code.RESOURCE_EXHAUSTED) {
            return new OcrProviderException("PROVIDER_RATE_LIMIT", "Vision rate limit exceeded.", true);
        }
        if (code == Code.UNAVAILABLE) {
            return new OcrProviderException("PROVIDER_UNAVAILABLE", "Vision is unavailable.", true);
        }
        return new OcrProviderException(retryable ? "PROVIDER_UNAVAILABLE" : "PROVIDER_FAILED",
                "Vision request failed.", retryable);
    }

    private static boolean retryable(Code code) {
        return code == Code.ABORTED || code == Code.DEADLINE_EXCEEDED || code == Code.INTERNAL
                || code == Code.RESOURCE_EXHAUSTED || code == Code.UNAVAILABLE;
    }
}
