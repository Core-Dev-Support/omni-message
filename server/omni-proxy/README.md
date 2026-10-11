# omni-proxy — сервер раздачи бесплатных MTProto-прокси

Референсный бэкенд для `OmniConnection` (флоу — как бесплатные ключи в NAUA Security Mirage).
Один файл, ноль зависимостей, запускается как Supabase Edge Function. Подойдёт и
голый Deno (`deno run -A index.ts`) — тогда слушай порт сам через `Deno.serve`
(в файле уже есть).

## Быстрый старт (Supabase, как у Mirage)

```bash
# 1. Положить свои mtproxy/mtg-адреса (секреты ee/dd + hex):
supabase secrets set \
  PROXY_LIST_JSON='[{"host":"proxy1.example.com","port":443,"secret":"ee..."},{"host":"proxy2.example.com","port":443,"secret":"dd..."}]' \
  REQUIRE_TOKEN=0

# 2. Деплой:
supabase functions deploy omni-proxy --no-verify-jwt

# 3. Прописать адрес в omni/app-config.json в репозитории:
#    { "api_base_url": "https://<project>.supabase.co/functions/v1/omni-proxy" }
```

Готово: клиенты начнут подтягивать список сами. Ничего пересобирать не надо —
`app-config.json` читается с GitHub raw по HTTPS при каждом старте (кеш суток).

## Откуда взять сами прокси

Сервер только **раздаёт** список, сами MTProto-прокси подними отдельно. Вариант,
который уже есть в организации, — те же VPS, что раздают VLESS для Mirage:

```bash
# mtg (простейший MTProto-прокси) на любом VPS:
docker run -d --restart unless-stopped -p 443:3128 \
  -e SECRET=$(openssl rand -hex 16 | sed 's/^/ee/') \
  nineseconds/mtg:latest run :3128
# секрет из логов: docker logs <id> | grep ee
```

2–3 таких инстанса в разных местах — и `PROXY_LIST_JSON` заполнен. Секреты
`ee`/`dd` — TLS-маскировка: DPI видит «обычный TLS», а не MTProto.

## Режимы

- `REQUIRE_TOKEN=0` (по умолчанию): open mode — список отдаётся любому девайсу.
  Для старта достаточно. Abuse-контроль — на твоей стороне (rate limit, ротация
  секретов).
- `REQUIRE_TOKEN=1` + `SHARED_SECRET=...`: токены привязаны к deviceId
  (подпись SHA-256, см. `makeToken`/`checkToken`). Чужой токен не подойдёт
  к чужому девайсу. Отзыв — сменой `SHARED_SECRET` (все токены сгорят разом).

## Локальная проверка без деплоя

```bash
PROXY_LIST_JSON='[{"host":"127.0.0.1","port":443,"secret":"ee00000000000000000000000000000000"}]' \
  deno run -A --watch index.ts
# в другом терминале:
curl -X POST localhost:8000/proxy/config -d '{"deviceId":"test"}'
# -> {"proxies":[...]}
```

## Что НЕ входит (осознанно)

- Биллинг/подписки — это бесплатная раздача, как free-узлы Mirage. Платный
  France-узел у Mirage живёт отдельно (`france-config` + `subscriptions`).
- Персональные секреты на девайс — список общий для всех. Персонализация —
  следующим шагом, если понадобится (выдача личного secret + его отзыв).
