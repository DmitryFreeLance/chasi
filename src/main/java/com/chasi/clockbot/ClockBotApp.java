package com.chasi.clockbot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Document;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.DeleteMessage;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.SendResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ClockBotApp {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();
    public static void main(String[] args) {
        Config config = Config.fromEnv();
        Database database = new Database(config.dbPath());
        GeminiClient geminiClient = new GeminiClient(config);
        TelegramBot bot = new TelegramBot(config.telegramToken());

        System.out.println("Clock photo bot started");

        bot.setUpdatesListener(updates -> handleUpdates(updates, bot, geminiClient, database, config),
            exception -> System.err.println("Updates listener error: " + exception.getMessage()));
    }

    private static int handleUpdates(Iterable<Update> updates, TelegramBot bot, GeminiClient geminiClient,
                                     Database database, Config config) {
        for (Update update : updates) {
            Message message = update.message();
            if (message == null) {
                continue;
            }

            if (isImageDocument(message.document())) {
                handleImageFile(message, message.document().fileId(), message.document().fileName(),
                    bot, geminiClient, database, config);
                continue;
            }

            if (message.photo() != null && message.photo().length > 0) {
                handlePhotoMessage(message, bot, geminiClient, database, config);
                continue;
            }

            if (message.text() != null) {
                handleTextMessage(message, bot);
                continue;
            }

            bot.execute(new SendMessage(message.chat().id(),
                "Пришлите фото часов, и я определю время."));
        }

        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private static void handleTextMessage(Message message, TelegramBot bot) {
        String text = message.text().trim();
        if ("/start".equalsIgnoreCase(text) || "/help".equalsIgnoreCase(text)) {
            bot.execute(new SendMessage(message.chat().id(),
                "Пришлите фото часов. Я отвечу временем в формате HH:MM."));
            return;
        }

        bot.execute(new SendMessage(message.chat().id(),
            "Нужна фотография часов. Отправьте изображение."));
    }

    private static void handlePhotoMessage(Message message, TelegramBot bot, GeminiClient geminiClient,
                                           Database database, Config config) {
        PhotoSize best = pickBestPhoto(message.photo());
        if (best == null) {
            bot.execute(new SendMessage(message.chat().id(),
                "Не удалось получить фото. Попробуйте еще раз."));
            return;
        }
        handleImageFile(message, best.fileId(), null, bot, geminiClient, database, config);
    }

    private static void handleImageFile(Message message, String fileId, String fileName, TelegramBot bot,
                                        GeminiClient geminiClient, Database database, Config config) {
        Long chatId = message.chat().id();
        Integer pendingMessageId = sendPendingMessage(bot, chatId);

        GetFileResponse getFileResponse = bot.execute(new GetFile(fileId));
        if (getFileResponse == null || !getFileResponse.isOk() || getFileResponse.file() == null) {
            deletePendingMessage(bot, chatId, pendingMessageId);
            bot.execute(new SendMessage(chatId,
                "Не удалось скачать фото. Попробуйте другое изображение."));
            return;
        }

        String filePath = getFileResponse.file().filePath();
        String imageUrl = "https://api.telegram.org/file/bot" + config.telegramToken() + "/" + filePath;
        String resolvedFileName = fileName != null && !fileName.isBlank()
            ? fileName
            : fileNameFromPath(filePath, fileId);
        byte[] imageBytes = downloadTelegramFile(imageUrl);
        ProcessedImage processed = ImagePreprocessor.preprocess(imageBytes, resolvedFileName);
        if (processed != null && processed.bytes() != null) {
            logImageSize(imageBytes, processed.bytes());
            imageBytes = processed.bytes();
            resolvedFileName = processed.fileName();
        }

        GeminiResult result = geminiClient.extractTime(imageUrl, imageBytes, resolvedFileName);
        String responseText = result.time().equals("UNKNOWN")
            ? "Не удалось определить время. Попробуйте другое фото."
            : result.time();

        deletePendingMessage(bot, chatId, pendingMessageId);
        bot.execute(new SendMessage(chatId, responseText));

        User user = message.from();
        database.logRequest(new RequestLog(
            user != null ? user.id() : null,
            user != null ? user.username() : null,
            fileId,
            imageUrl,
            result.time(),
            result.status(),
            result.errorMessage()
        ));
    }

    private static Integer sendPendingMessage(TelegramBot bot, Long chatId) {
        SendResponse response = bot.execute(new SendMessage(chatId, "Пишу ответ..."));
        if (response == null || !response.isOk() || response.message() == null) {
            return null;
        }
        return response.message().messageId();
    }

    private static void deletePendingMessage(TelegramBot bot, Long chatId, Integer messageId) {
        if (messageId == null) {
            return;
        }
        bot.execute(new DeleteMessage(chatId, messageId));
    }

    private static PhotoSize pickBestPhoto(PhotoSize[] photos) {
        PhotoSize best = null;
        if (photos == null) {
            return null;
        }
        for (PhotoSize photo : photos) {
            if (best == null) {
                best = photo;
                continue;
            }
            Long bestSize = best.fileSize();
            Long photoSize = photo.fileSize();
            if (bestSize == null || photoSize == null) {
                best = photo;
            } else if (photoSize > bestSize) {
                best = photo;
            }
        }
        return best;
    }

    private static boolean isImageDocument(Document document) {
        if (document == null) {
            return false;
        }
        String mime = document.mimeType();
        return mime != null && mime.startsWith("image/");
    }

    private static byte[] downloadTelegramFile(String url) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        try {
            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Failed to download Telegram file. Status=" + response.statusCode());
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Download interrupted: " + e.getMessage());
            return null;
        } catch (IOException e) {
            System.err.println("Download failed: " + e.getMessage());
            return null;
        }
    }

    private static String fileNameFromPath(String filePath, String fallback) {
        if (filePath == null || filePath.isBlank()) {
            return fallback + ".jpg";
        }
        int index = filePath.lastIndexOf('/');
        String name = index >= 0 ? filePath.substring(index + 1) : filePath;
        if (name.isBlank()) {
            return fallback + ".jpg";
        }
        return name;
    }

    private static void logImageSize(byte[] original, byte[] processed) {
        if (original == null || processed == null) {
            return;
        }
        if (original.length == processed.length) {
            return;
        }
        System.out.println("[ClockBot] Image bytes " + original.length + " -> " + processed.length);
    }
}
