# Clock Photo Telegram Bot

Телеграм-бот на Java определяет время по фотографии часов, используя Gemini 3 Pro через Kie.ai.

## Переменные окружения

- `TELEGRAM_BOT_TOKEN` — токен Telegram бота.
- `KIE_API_KEY` — API ключ Kie.ai.
- `KIE_API_BASE_URL` — базовый URL API (по умолчанию `https://api.kie.ai`).
- `DB_PATH` — путь к SQLite базе (по умолчанию `data/bot.db`).
- `GEMINI_SYSTEM_PROMPT` — системный промпт (по умолчанию задан в коде).

## Локальный запуск

```bash
mvn -q -DskipTests package
TELEGRAM_BOT_TOKEN=... KIE_API_KEY=... java -jar target/clock-photo-bot.jar
```

## Docker

Сборка:

```bash
docker build -t clock-photo-bot .
```

Запуск:

```bash
docker run --rm \
  -e TELEGRAM_BOT_TOKEN=... \
  -e KIE_API_KEY=... \
  -e KIE_API_BASE_URL=https://api.kie.ai \
  -v $(pwd)/data:/app/data \
  clock-photo-bot
```
