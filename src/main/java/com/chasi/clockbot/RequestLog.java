package com.chasi.clockbot;

public record RequestLog(Long userId,
                         String username,
                         String fileId,
                         String imageUrl,
                         String resultTime,
                         String status,
                         String errorMessage) {
}
