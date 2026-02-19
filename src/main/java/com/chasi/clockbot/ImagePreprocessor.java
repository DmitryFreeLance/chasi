package com.chasi.clockbot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

public class ImagePreprocessor {
    private static final int MAX_DIMENSION = 1280;
    private static final long MAX_BYTES_BEFORE = 2_000_000;

    public static ProcessedImage preprocess(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return new ProcessedImage(bytes, fileName);
        }

        BufferedImage image = readImage(bytes);
        if (image == null) {
            return new ProcessedImage(bytes, fileName);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int maxSide = Math.max(width, height);
        boolean shouldResize = maxSide > MAX_DIMENSION;
        boolean shouldReencode = bytes.length > MAX_BYTES_BEFORE;

        if (!shouldResize && !shouldReencode) {
            return new ProcessedImage(bytes, fileName);
        }

        double scale = shouldResize ? (double) MAX_DIMENSION / maxSide : 1.0;
        int newWidth = (int) Math.max(1, Math.round(width * scale));
        int newHeight = (int) Math.max(1, Math.round(height * scale));

        BufferedImage output = shouldResize ? resize(image, newWidth, newHeight) : toRgb(image);
        byte[] encoded = encodeJpeg(output, 0.85f);
        if (encoded == null || encoded.length == 0) {
            return new ProcessedImage(bytes, fileName);
        }

        String newName = replaceExtension(fileName, "jpg");
        return new ProcessedImage(encoded, newName);
    }

    private static BufferedImage readImage(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(input);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = target.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(source, 0, 0, width, height, null);
        g2d.dispose();
        return target;
    }

    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = target.createGraphics();
        g2d.drawImage(source, 0, 0, null);
        g2d.dispose();
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return null;
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            writer.dispose();
            return output.toByteArray();
        } catch (IOException e) {
            writer.dispose();
            return null;
        }
    }

    private static String replaceExtension(String fileName, String extension) {
        String name = fileName == null ? "photo" : fileName.trim();
        if (name.isEmpty()) {
            name = "photo";
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name + "." + extension;
    }
}
