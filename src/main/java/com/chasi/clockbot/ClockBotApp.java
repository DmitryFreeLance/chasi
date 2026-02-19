package com.chasi.clockbot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.DeleteMessage;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.SendResponse;

public class ClockBotApp {
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
        Long chatId = message.chat().id();
        Integer pendingMessageId = sendPendingMessage(bot, chatId);

        PhotoSize best = pickBestPhoto(message.photo());
        if (best == null) {
            deletePendingMessage(bot, chatId, pendingMessageId);
            bot.execute(new SendMessage(chatId,
                "Не удалось получить фото. Попробуйте еще раз."));
            return;
        }

        String fileId = best.fileId();
        GetFileResponse getFileResponse = bot.execute(new GetFile(fileId));
        if (getFileResponse == null || !getFileResponse.isOk() || getFileResponse.file() == null) {
            deletePendingMessage(bot, chatId, pendingMessageId);
            bot.execute(new SendMessage(chatId,
                "Не удалось скачать фото. Попробуйте другое изображение."));
            return;
        }

        String filePath = getFileResponse.file().filePath();
        String imageUrl = "https://api.telegram.org/file/bot" + config.telegramToken() + "/" + filePath;

        GeminiResult result = geminiClient.extractTime(imageUrl);
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
}
