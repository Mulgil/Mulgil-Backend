package com.mulgil.ocr;

import java.util.List;

public interface VisionOcrPort {
    OcrResult extract(byte[] image);

    record OcrResult(List<OcrBlock> blocks, String provider, String model) {
        public OcrResult {
            blocks = List.copyOf(blocks);
        }
    }

    record OcrBlock(String text, double confidence, NormalizedBox box) {}

    record NormalizedBox(double x, double y, double width, double height) {}
}
