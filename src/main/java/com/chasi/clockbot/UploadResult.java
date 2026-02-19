package com.chasi.clockbot;

public record UploadResult(boolean success, String downloadUrl, String fileUrl, String errorMessage) {
    public static UploadResult ok(String downloadUrl, String fileUrl) {
        return new UploadResult(true, downloadUrl, fileUrl, null);
    }

    public static UploadResult error(String message) {
        return new UploadResult(false, null, null, message);
    }
}
