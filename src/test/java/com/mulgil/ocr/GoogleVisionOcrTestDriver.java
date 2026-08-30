package com.mulgil.ocr;

import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.rpc.Code;
import com.google.rpc.Status;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class GoogleVisionOcrTestDriver {
    private GoogleVisionOcrTestDriver() {}

    public static VisionOcrPort permissionDenied() {
        ImageAnnotatorClient client = mock(ImageAnnotatorClient.class);
        when(client.batchAnnotateImages(org.mockito.ArgumentMatchers.<AnnotateImageRequest>anyList()))
                .thenReturn(BatchAnnotateImagesResponse.newBuilder().addResponses(
                        AnnotateImageResponse.newBuilder().setError(Status.newBuilder()
                                .setCode(Code.PERMISSION_DENIED_VALUE).build()).build()).build());
        return new GoogleVisionOcrAdapter(client, "DOCUMENT_TEXT_DETECTION");
    }
}
