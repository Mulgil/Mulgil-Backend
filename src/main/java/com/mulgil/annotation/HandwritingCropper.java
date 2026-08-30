package com.mulgil.annotation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class HandwritingCropper {
    byte[] crop(byte[] pdf, int pageNumber, double x, double y, double width, double height) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(pageNumber - 1, 150, ImageType.RGB);
            int left = Math.max(0, (int) Math.floor(x * page.getWidth()));
            int top = Math.max(0, (int) Math.floor(y * page.getHeight()));
            int right = Math.min(page.getWidth(), (int) Math.ceil((x + width) * page.getWidth()));
            int bottom = Math.min(page.getHeight(), (int) Math.ceil((y + height) * page.getHeight()));
            ImageIO.write(page.getSubimage(left, top, right - left, bottom - top), "png", output);
            return output.toByteArray();
        }
    }
}
