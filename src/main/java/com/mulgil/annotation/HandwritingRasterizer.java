package com.mulgil.annotation;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

final class HandwritingRasterizer {
    private static final int PAGE_PIXELS = 1000;

    byte[] render(Input input) throws IOException {
        int width = Math.max(1, (int) Math.round(input.box().width() * PAGE_PIXELS));
        int height = Math.max(1, (int) Math.round(input.box().height() * PAGE_PIXELS));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            input.strokes().forEach(stroke -> draw(graphics, input.box(), stroke));
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static void draw(Graphics2D graphics, Box box, Stroke stroke) {
        List<Point> points = stroke.points();
        double diameter = Math.max(1, stroke.widthNorm() * PAGE_PIXELS);
        graphics.setStroke(new BasicStroke((float) diameter, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (points.size() == 1) {
            Point point = points.getFirst();
            graphics.fill(new Ellipse2D.Double(x(point, box) - diameter / 2,
                    y(point, box) - diameter / 2, diameter, diameter));
            return;
        }
        Path2D path = new Path2D.Double();
        path.moveTo(x(points.getFirst(), box), y(points.getFirst(), box));
        points.stream().skip(1).forEach(point -> path.lineTo(x(point, box), y(point, box)));
        graphics.draw(path);
    }

    private static double x(Point point, Box box) {
        return (point.x() - box.x()) * PAGE_PIXELS;
    }

    private static double y(Point point, Box box) {
        return (point.y() - box.y()) * PAGE_PIXELS;
    }

    record Input(Box box, List<Stroke> strokes) {
        Input {
            strokes = List.copyOf(strokes);
        }
    }
    record Box(double x, double y, double width, double height) {}
    record Stroke(double widthNorm, List<Point> points) {
        Stroke {
            points = List.copyOf(points);
        }
    }
    record Point(double x, double y) {}
}
