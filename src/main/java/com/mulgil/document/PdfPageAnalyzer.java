package com.mulgil.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class PdfPageAnalyzer {
    List<Page> analyze(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<Page> pages = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                stripper.setStartPage(index + 1);
                stripper.setEndPage(index + 1);
                PDPage page = document.getPage(index);
                ImageCoverage coverage = new ImageCoverage(page);
                coverage.processPage(page);
                pages.add(new Page(index + 1, stripper.getText(document).strip(), coverage.ratio()));
            }
            return List.copyOf(pages);
        }
    }

    byte[] render(byte[] pdf, int pageNumber) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new PDFRenderer(document).renderImageWithDPI(pageNumber - 1, 150, ImageType.RGB),
                    "png", output);
            return output.toByteArray();
        }
    }

    record Page(int number, String text, double imageCoverage) {}

    private static final class ImageCoverage extends PDFGraphicsStreamEngine {
        private double imageArea;

        private ImageCoverage(PDPage page) {
            super(page);
        }

        private double ratio() {
            double pageArea = getPage().getCropBox().getWidth() * getPage().getCropBox().getHeight();
            return pageArea == 0 ? 0 : Math.min(1, imageArea / pageArea);
        }

        @Override
        public void drawImage(PDImage image) {
            Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
            Point2D p0 = matrix.transformPoint(0, 0);
            Point2D p1 = matrix.transformPoint(1, 0);
            Point2D p2 = matrix.transformPoint(1, 1);
            Point2D p3 = matrix.transformPoint(0, 1);
            imageArea += Math.abs(p0.getX() * p1.getY() + p1.getX() * p2.getY()
                    + p2.getX() * p3.getY() + p3.getX() * p0.getY()
                    - p1.getX() * p0.getY() - p2.getX() * p1.getY()
                    - p3.getX() * p2.getY() - p0.getX() * p3.getY()) / 2;
        }

        @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {}
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) {}
        @Override public void lineTo(float x, float y) {}
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public Point2D getCurrentPoint() { return null; }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) {}
    }
}
