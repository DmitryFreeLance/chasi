package com.chasi.clockbot;

public class MimeTypeResolver {
    public static String fromFileName(String fileName) {
        if (fileName == null) {
            return "image/jpeg";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }
}
