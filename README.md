# Interview Scheduler API

Учебный backend для записи на собеседования: рекрутер публикует интервалы, кандидат бронирует и переносит встречу. Проект демонстрирует Java, Spring Boot, работу с PostgreSQL, транзакциями и REST API.

## Возможности

- Регистрация кандидата или рекрутера, вход и JWT-токен на 1 час; пароли хранятся в виде BCrypt-хешей.
- Рекрутер создаёт будущие интервалы без пересечений. Авторизованные пользователи просматривают список слотов с пагинацией.
- Кандидат бронирует свободный слот. При одновременных запросах строка блокируется в транзакции: только один запрос может успешно занять конкретный слот. Пересекающиеся записи одного кандидата у разных рекрутеров также запрещены.
- Кандидат или рекрутер, которому принадлежит слот, отменяет запись. Кандидат переносит свою запись на свободный слот того же рекрутера; перенос выполняется одной транзакцией.
- Flyway-миграция, интеграционные тесты API, Docker Compose и CI с PostgreSQL.

## Стек

Java 21, Spring Boot 3, Spring Security, Spring Data JPA, PostgreSQL, Flyway, Maven, JUnit 5, Docker Compose.

## Запуск через Docker

Требуются Docker и Docker Compose. Из папки проекта:

```bash
# Сгенерируйте отдельный секрет и сохраните его только локально.
printf 'JWT_SECRET=%s\n' "$(openssl rand -hex 32)" > .env
docker compose up --build
```

API доступно по адресу `http://localhost:8080`. Для остановки выполните `docker compose down`. Данные БД сохраняются в Docker volume. `docker compose down -v` также удалит данные.

## Запуск тестов

Нужны JDK 21 и Maven 3.9+:

```bash
mvn test
```

По умолчанию тесты запускаются на H2. Чтобы запустить тот же набор на отдельной **тестовой** базе PostgreSQL, задайте `TEST_DATABASE_URL`, `TEST_DATABASE_USER` и `TEST_DATABASE_PASSWORD`, затем запустите `mvn verify`. Flyway создаст таблицы в указанной базе. В GitHub Actions используется PostgreSQL 16.

## Пример работы с API

Время указывается в ISO 8601 с часовым поясом, например `2030-01-15T10:00:00Z` (замените даты на будущие). Команды используют `curl` и `python3`.

1. Создайте рекрутера и кандидата (email должен быть уникальным):

```bash
curl -i -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"recruiter@example.com","password":"password123","role":"RECRUITER"}'
curl -i -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"candidate@example.com","password":"password123","role":"CANDIDATE"}'
```

2. Получите токены:

```bash
RECRUITER_TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"recruiter@example.com","password":"password123"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
CANDIDATE_TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"candidate@example.com","password":"password123"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
```

3. Создайте слот и запомните его `id` из ответа:

```bash
curl -X POST localhost:8080/api/slots -H "Authorization: Bearer $RECRUITER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"startsAt":"2030-01-15T10:00:00Z","endsAt":"2030-01-15T10:30:00Z"}'
```

4. Посмотрите список и запишитесь, заменив `1` на полученный id:

```bash
curl 'localhost:8080/api/slots?page=0&size=20' -H "Authorization: Bearer $CANDIDATE_TOKEN"
curl -X POST localhost:8080/api/slots/1/book -H "Authorization: Bearer $CANDIDATE_TOKEN"
```

5. Для переноса сначала создайте второй слот у того же рекрутера, а затем укажите его id:

```bash
curl -X POST localhost:8080/api/slots/1/reschedule -H "Authorization: Bearer $CANDIDATE_TOKEN" \
  -H 'Content-Type: application/json' -d '{"targetSlotId":2}'
```

Чтобы отменить запись: `curl -X DELETE localhost:8080/api/slots/2/booking -H "Authorization: Bearer $CANDIDATE_TOKEN"`.

## API

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/api/auth/register` | Регистрация: email, password, role |
| POST | `/api/auth/login` | Получение JWT |
| POST | `/api/slots` | Создать слот; только рекрутер |
| GET | `/api/slots` | Список слотов с пагинацией (`page`, `size`) |
| POST | `/api/slots/{id}/book` | Забронировать; только кандидат |
| DELETE | `/api/slots/{id}/booking` | Отменить запись; кандидат или владелец слота |
| POST | `/api/slots/{id}/reschedule` | Перенести свою запись; `targetSlotId` в теле |

Типичные ответы: `400` — неверные данные, `401` — отсутствует или неверен токен, `403` — нет прав, `404` — слот не найден, `409` — конфликт состояния. Список показывает, занят ли слот; идентификатор кандидата видят только он сам и рекрутер этого слота.

## Как устроен проект

`SlotController` отвечает за HTTP-запросы, `SlotService` — за правила записи и границы транзакций, репозитории — за доступ к базе. Сущности хранятся в PostgreSQL; схему и индексы создаёт Flyway.

Чтобы защититься от гонок, создание слотов сериализуется блокировкой строки рекрутера; бронирование и перенос — строки кандидата, затем нужного слота. При переносе два слота блокируются в порядке возрастания ID. Интервалы считаются полуоткрытыми: слот, заканчивающийся в 10:30, может соседствовать со слотом, начинающимся в 10:30. При конфликте или другой ошибке транзакция откатывается.

`GET /api/slots` возвращает объект с `content`, `page`, `size`, `totalElements` и `totalPages`. Нумерация страниц начинается с нуля, размер по умолчанию — 20, максимум — 100; сортировка по времени начала и ID. Для регистрации пароль должен содержать не менее 8 символов и не более 72 байт в UTF-8.

## Границы проекта

Это портфолио, а не готовый сервис для реального найма. Роль рекрутера можно выбрать при регистрации, почта не подтверждается, ограничения частоты и отзыв токенов не реализованы. Все слоты доступны всем авторизованным пользователям; это упрощение для демонстрации, а не модель приватного корпоративного календаря. Для реального применения потребуются управление правами, проверка пересечений, защита от перебора паролей и другие доработки.
