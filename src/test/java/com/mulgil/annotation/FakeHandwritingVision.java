package com.mulgil.annotation;

import com.mulgil.ocr.OcrProviderException;
import com.mulgil.ocr.VisionOcrPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

final class FakeHandwritingVision implements VisionOcrPort {
    private OcrResult result;
    private int width;
    private int height;
    private int inkPixels;
    private int whitePixels;
    private OcrProviderException failure;

    void result(String text, double confidence) {
        result = new OcrResult(List.of(new OcrBlock(text, confidence,
                new NormalizedBox(0, 0, 1, 1))), "fake-vision", "fake-handwriting");
        width = 0;
        height = 0;
        inkPixels = 0;
        whitePixels = 0;
        failure = null;
    }

    void fail(OcrProviderException value) { failure = value; }

    int width() { return width; }
    int height() { return height; }
    int inkPixels() { return inkPixels; }
    int whitePixels() { return whitePixels; }

    @Override
    public OcrResult extract(byte[] image) {
        if (failure != null) throw failure;
        try {
            BufferedImage raster = ImageIO.read(new ByteArrayInputStream(image));
            width = raster.getWidth();
            height = raster.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if ((raster.getRGB(x, y) & 0xffffff) == 0xffffff) whitePixels++;
                    else inkPixels++;
                }
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
