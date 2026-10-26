# Third-party notices

| Компонент | Назначение | Лицензия |
|---|---|---|
| Spring Boot, Spring Cloud OpenFeign, Spring AI | backend | Apache-2.0 |
| Liquibase | миграции БД | Apache-2.0 |
| PostgreSQL 18 | хранилище | PostgreSQL License |
| Qdrant 1.19 | векторный поиск (RAG) | Apache-2.0 |
| Keycloak 26 | аутентификация | Apache-2.0 |
| nginx | обратный прокси | BSD-2-Clause |
| React, Vite | frontend | MIT |
| FastAPI, Uvicorn | ML-сервис | MIT, BSD-3-Clause |
| httpx | HTTP-клиент | BSD-3-Clause |
| PyMuPDF | разбор PDF | AGPL-3.0 |
| Tesseract OCR (rus, eng) | распознавание сканов PDF | Apache-2.0 |
| fastembed | инференс эмбеддингов | Apache-2.0 |
| intfloat/multilingual-e5-small | модель эмбеддингов (384) | MIT |
| feedparser | чтение RSS | BSD-2-Clause |
| trafilatura | извлечение текста статей | Apache-2.0 |

## Модели LLM
| Модель | Где используется | Условия |
|---|---|---|
| `z-ai/glm-5.3-flash` | генерация инсайта: план, черновик, критик, доработка (`spring.ai.openai.chat.model`, `LLM_INSIGHTS_MODEL`) | модель из разрешённого списка кейса, доступ по ключу организаторов |

Другие модели из разрешённого списка в `.env.example` (`ML_FACTS_MODEL`, `LLM_FOLLOW_UP_MODEL`, `LLM_RANKING_MODEL`) зарезервированы под отдельные задачи и в текущей версии не вызываются.

## Внешние сервисы
| Сервис | Назначение | Условия |
|---|---|---|
| LLM-шлюз организаторов `https://changellenge.up.railway.app/v1` | OpenAI-совместимый API для вызова LLM (`spring.ai.openai.base-url`, `LLM_API_URL`) | ключ `OPENAI_API_KEY` выдают организаторы хакатона, дневной лимит 500 ₽; ключ не хранится в репозитории |
| Hugging Face Hub | однократная загрузка модели `intfloat/multilingual-e5-small` при первом запуске ML-сервиса | публичная модель, MIT |

## Данные
- Данные о компаниях, группах и платежах — синтетические обезличенные данные кейса (`backend/core/src/main/resources/data/csv`).
- Аналитические материалы Альфа-Банка — PDF, предоставленные в рамках кейса.
- Новости — публичные RSS-ленты: Банк России, Интерфакс, ТАСС. Хранятся заголовок, текст, ссылка на первоисточник и дата.

## Смоделированные интеграции
Интернет-банк Альфа-Бизнеса представлен собственным frontend-прототипом; продуктовые данные банка смоделированы.
