# Раздача бесплатных прокси: контракт клиент ↔ сервер

Флоу скопирован 1 в 1 с бесплатных ключей NAUA Security Mirage (а там — с vpGram).
Таблица соответствия (чтобы было видно, что изучено, а не выдумано):

| Mirage (`naua-security-mirage-android`, ветка `main`) | Omni (`OmniConnection.java`) |
|---|---|
| `app/.../data/supabase/SupabaseConfig.kt` + `AppConfigEndpoints.kt`: `app-config.json` с тройным фолбэком GitHub raw → GCS → Yandex | `CONFIG_MIRRORS`: `omni/app-config.json` с GitHub raw (+ место под зеркала); override через `OMNI_PROXY_URL` |
| `VpnSDK.registerOrAuth(maxAttempts=3)`, бэкофф 1/2/4 c | `register()`: те же 3 попытки, те же паузы |
| `GET .../client-api/v1/download-anonymous-key` (`anonymous_key_relative_path`) | `anonKey()`: тот же путь, опционален, уходит заголовком `X-Anonymous-Key` |
| `VlessKeyRepository`: POST `{deviceId}` (UUID из `DeviceIdRepository`, UA `Chrome/120`) | POST `{deviceId, token, app, v}` с тем же UA |
| Ответ — Xray-конфиг `{config:{outbounds}}` / `v2rayKey` | Ответ — `{proxies:[{host,port,secret}]}` (проще: MTProto не нужен Xray) |
| `free_panel_cache.json`, 256 КБ, TTL 10 мин; fallback — 3 захардкоженных узла | кеш prefs, TTL 6 ч; fallback — `bundledFallback()` |
| `PingRepository.measureAllPings`, перебор до `CONNECTED` | `pingAndPick()` через `ConnectionsManager.checkProxy` |

## Endpoints

База (`api_base_url`): либо `OMNI_PROXY_URL` (Secrets/local.properties), либо из
`omni/app-config.json`, либо кеш. Все тела — JSON, все ответы — JSON.

### `GET {api}/client-api/v1/download-anonymous-key`

→ `{"key": "..."}` или `{"key": ""}`. Опционален: нет эндпоинта — работаем без ключа.

### `POST {api}/auth/register`

Тело: `{"deviceId": "<uuid>", "app": "omni-message", "v": 1}`

→ `{"token": "<opaque>"}` или `{"token": ""}` (open mode).
Клиент повторяет до 3 раз с паузами 1/2/4 c, токен кеширует на сутки.

### `POST {api}/proxy/config`

Тело: `{"deviceId": "<uuid>", "token": "<из register>", "app": "omni-message", "v": 1}`

→ `{"proxies": [{"host": "...", "port": 443, "secret": "ee..."}]}`

Требования к элементам: непустой host, порт 1–65535, непустой secret
(клиент валидирует через `ProxySettings.isValid()` и мусор отбрасывает).

## Поведение клиента (коротко)

1. Bootstrap базы → register (кеш токена сутки) → fetch списка.
2. Пустой ответ / ошибка / нет сервера → кеш (6 ч) → встроенный fallback.
3. Кандидаты пингуются, быстрейший включается штатно (весь tgnet-трафик).
4. Висение >15 c — ротация по кругу. Kill-switch — никогда не снимать самим.

## Безопасность

- URL подписки и токены лежат в prefs приложения — как у Mirage. Рута достаточно,
  чтобы их достать; это нормально для бесплатной раздачи.
- Подпись списка Ed25519 — запланирована (PLAN.md D3), в v1 её нет: сервер
  доверенный (свой), зеркала — только discovery `api_base_url`, не сам список.
- Abuse-контроль — на сервере: `REQUIRE_TOKEN=1`, rate limit, ротация секретов
  прокси. Клиент шлёт стабильный `deviceId` (UUID, генерится один раз).
