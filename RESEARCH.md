# Omni Message — исследование неофициальных клиентов Telegram

Дата: 2026-09-30
Цель: изучить, как устроены неофициальные клиенты Telegram (AyuGram, exteraGram, CherryGram, vpGram и др.), найти клиентов, работающих в РФ «из коробки», и выбрать базу для собственного приложения **Omni Message** (пакет `com.naua_omni_message.app`, ABI: `armeabi-v7a` + `arm64-v8a`).

---

## 1. Контекст: Telegram в России (2025–2026)

| Период | Событие |
|---|---|
| Авг 2025 | Ограничены аудио-/видеозвонки; далее — замедление через ТСПУ |
| 10 фев 2026 | РКН официально подтвердил замедление по всей стране |
| 14–17 мар 2026 | Массовые жалобы, мессенджер «не работает ни в каком виде» |
| 1 апр 2026 | Полная блокировка (план, о котором писали «База»/СМИ) |
| 10 апр 2026 | OONI: уровень аномалий ~95% — блокировка сильнее, чем у WhatsApp и Signal |

- Блокировка реализуется через **ТСПУ** (DPI-фильтры у провайдеров), а не грубым резанием IP как в 2018.
- Официальный клиент **без прокси/VPN не работает**.
- Дуров (апр 2026): ~65 млн россиян ежедневно заходят через VPN; команда Telegram делает трафик «сложнее для обнаружения и блокировки».
- Обходы перегружают ТСПУ «мусорным» трафиком — блокировка «ломается» об сам факт массового обхода.

Источники: [Википедия — Блокировка Telegram в России (2026)](https://ru.wikipedia.org/wiki/Блокировка_Telegram_в_России_(2026)), [CNews](https://www.cnews.ru/news/top/2026-03-12_pozvolyayushchij_obhodit_blokirovku), [AppleInsider.ru](https://appleinsider.ru/tips-tricks/blokirovka-telegram-v-rossii-chto-proishodit-v-aprele-2026.html), [URA.RU](https://ura.news/news/1053081464)

---

## 2. Разбор названных клиентов

### 2.1. AyuGram
- **Репозитории:** [AyuGram4A (Android)](https://github.com/AyuGram/AyuGram4A), [AyuGramDesktop](https://github.com/AyuGram/AyuGramDesktop), [органицация](https://github.com/ayugram)
- **База:** Android-версия построена **поверх exteraGram** (т.е. в конечном счёте форк официального DrKLO/Telegram); десктоп — форк Telegram Desktop.
- **Ключевые фичи:** полный **ghost mode** (скрытый онлайн, чтение без «прочитано», набор текста не виден), **anti-recall** (удалённые у собеседника сообщения сохраняются локально), история сообщений, **локальный Telegram Premium**, переводчик, стример-режим, кастомизация шрифтов.
- **Как реализовано (архитектурно):** клиент локально подавляет/откладывает RPC о статусах (`messages.readHistory`, `updateStatus`, typing-события), игнорирует события удаления (сообщение остаётся в локальной БД), а «локальный премиум» — подмена клиентских проверок флагов Premium (то, что проверяется на клиенте, разблокируется; серверные лимиты обойти нельзя).
- Есть известные баги ghost mode (сброс непрочитанных) — issues #134, #249.

### 2.2. exteraGram
- **Репозиторий:** [exteraSquad/exteraGram](https://github.com/exteraSquad/exteraGram), сайт [exteragram.app](https://exteragram.app/), десктоп-форк [xmdnx/exteraGramDesktop](https://github.com/xmdnx/exteraGramDesktop)
- **База:** прямой форк официального Telegram для Android.
- **Ключевые фичи:** динамические темы, глубокая кастомизация интерфейса, ускорение загрузки файлов, «лучшая камера», современный дизайн.
- ⚠️ **Важно:** у exteraGram есть **проприетарные фичи, не входящие в открытый код** (подтверждается дисклеймером форка SmartGram). Открытая часть — GPL, но полный продукт — нет.
- Производный форк: [smarts-uz/SmartGram](https://github.com/smarts-uz/SmartGram) (AyuGram4A + exteraGram без проприетарщины) — пример того, как комбинируют форки.

### 2.3. CherryGram
- **Репозиторий:** [arsLan4k1390/Cherrygram](https://github.com/arsLan4k1390/Cherrygram) (Java/Kotlin), канал @Cherry_gram (~41K), есть в Google Play; APK-зеркала (v12.5.1, Android 6+)
- **База:** форк официального Telegram App для Android («немного, но полезных модификаций»).
- **Ключевые фичи:** iPhone-стиль нижних табов, скрытие номера телефона/онлайна/просмотра сторис, **показ удалённых сообщений**, защита медиа от удаления, локальный премиум, ускорение загрузки, сохранение любых сторис, продвинутые настройки (Cherrygram Preferences), улучшенный сервис уведомлений.
- Существуют open-source зеркала ([Qing0721/Cherrygram](https://github.com/Qing0721/Cherrygram), Codeberg) — их авторы рекомендуют именно открытые сборки из соображений приватности.

### 2.4. vpGram — эталон «работает в РФ из коробки»
- **Где:** [GitHub vpgram/vpgram-android](https://github.com/vpgram/vpgram-android) (ветка `vpgram-public`, **GPL-2.0**), сайт [vpgram.website](https://vpgram.website), Google Play (`click.vpgram.messenger`, 100 000+), APKPure; есть Windows-версия и сборка для Huawei (без GMS).
- **База:** форк официального Telegram Android (~587 коммитов поверх апстрима).
- **Механизм обхода:** отдельный Gradle-модуль **`vpn/`** с подмодулями `base`, `network`, `proxy`, `sdk` — встроенный VPN/прокси-слой на **собственной инфраструктуре вне России**. Никаких настроек от пользователя не требуется; бесплатно.
- Заявляют «код открыт, никаких скрытых функций» и отсутствие доступа к переписке (шифрование остаётся штатным Telegram E2E/MTProto).
- **Урок для нас:** рабочая схема «из коробки» = форк + собственный прокси-модуль + серверы вне РФ.

---

## 3. Другие клиенты, актуальные для РФ

| Клиент | Что известно | Доверие/приватность |
|---|---|---|
| **Telega («Телега»)** | АО «Телега», 1 млн+ установок, топ RuStore; обход без VPN; прокси настраиваются через публичный сервис VK (mvk.com), звонки через VK Calls SDK | ⚠️ По исследованию RKS Global: **тайно подменяет серверы Telegram на свои, весь MTProto-трафик идёт через российские прокси**; трекеры MyTracker/OK.ru; «чёрный список» каналов в коде |
| **Telegraph / Graph Messenger** (ir.ilmili.telegraph) | Встроенные прокси, работает в РФ, RuStore + Google Play | ⚠️ 6 рекламных SDK, Firebase Analytics, отправка данных на серверы в РФ |
| **iMe** | TDLib-клиент со своей инфраструктурой | ⚠️ 15+ SDK, ad.mail.ru (VK), данные в РФ |
| **Nullgram, Nekogram, TurboTel** | Чистые форки (без телеметрии VK/РФ-серверов) | Чище всего, но **встроенного обхода блокировок нет** |
| **Plus Messenger** | Старейший форк | 1 рекламный SDK + Firebase Analytics |
| **Monogram** (новый, 2026) | Kotlin + Jetpack Compose + Material 3 + **TDLib**, **GPLv3**, [Habr](https://habr.com/ru/news/1016316/) | Открытый, прозрачный; в активной разработке |
| Zastogram, OwpenGram, MomoGram, AxoGram, Inugram | Мелкие форки из той же экосистемы | Разрозненная информация |

**Вывод по доверию:** рынок РФ-клиентов разделён: либо «работает из коробки, но закрыт/телеметрия/чужие прокси» (Telega, Graph, iMe), либо «чистый открытый код, но без обхода» (Nullgram, Nekogram, Monogram). **Свободная ниша: открытый форк с прозрачным обходом** — это и есть позиционирование Omni Message.

Источники: [CNews про Telega](https://www.cnews.ru/news/top/2026-03-12_pozvolyayushchij_obhodit_blokirovku), [RKS Global — исследование альтернативных клиентов](https://rks.global/ru/research/alt-telegram-clients/), [Habr — Monogram](https://habr.com/ru/news/1016316/), [4PDA — Telegraph](https://4pda.to/forum/index.php?showtopic=839842), [vc.ru — обзор 9 клиентов](https://vc.ru/telegram/1831259-9-storonnih-klientov-telegram-dlya-android-vo-vsem-luchshe-originalnogo)

---

## 4. Как технически устроен обход

1. **MTProto-прокси** — родной механизм Telegram: ссылка `t.me/proxy?server=HOST&port=PORT&secret=...`.
   - Секрет с префиксом `ee` — трафик заворачивается в TLS с SNI «разрешённого» домена (domain fronting);
   - префикс `dd` — режим со случайным паддингом, трафик выглядит как неструктурированный шум (DPI не находит сигнатур MTProto).
2. **Bootstrap-проблема:** если IP Telegram заблокированы, список прокси нельзя получить из Telegram — его берут **по обычному HTTPS** (GitHub, gist, свой сайт) или шипят в APK зашитый fallback-прокси. Так «из коробки» делают все жизнеспособные клиенты.
3. **Собственная инфраструктура** (путь vpGram): набор своих MTProto-proxy/VPN-серверов вне РФ + авто-выбор в клиенте. Дороже (серверы), но не зависит от публичных списков.
4. Официальный Telegram тоже умеет прокси (и Дуров обещал усложнить обнаружение трафика) — но вручную; авто-режима «из коробки» нет.

---

## 5. Два пути архитектуры для Omni Message

### Путь A — форк DrKLO/Telegram (рекомендую)
- **База:** [DrKLO/Telegram](https://github.com/DrKLO/Telegram), лицензия **GPL-2.0**.
- Именно так сделаны AyuGram, exteraGram, CherryGram, vpGram, Plus Messenger, Nullgram, NekoX и почти весь рынок.
- **Требования к сборке** (актуальный README): Android Studio 2025.1.4, **NDK 27.2.12479018**, SDK 36; `git clone --recursive --shallow-submodules`; свой `release.keystore` в `TMessagesProj/config`, пароли в `gradle.properties`, `google-services.json` (Firebase), заполнить `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`. В репо лежат dummy-ключи для воспроизводимых сборок — их обязательно заменить.
- **Структура:** главный модуль `TMessagesProj` (Java/Kotlin), нативный C/C++ (libtmessages и др., собирается NDK), флейворы `Afat`/`Debug`/`standalone`/`Huawei` и т.д.
- **Плюсы:** готовый полный функционал (звонки, сторис, секретные чаты, стриминг), миллионы строк уже написаны, все «фичи форков» (ghost mode и пр.) реализуются точечными патчами поверх.
- **Минусы:** кодовая база-монолит (главная претензия авторов Monogram), ребейзы на апстрим обязательны (security-фиксы выходят регулярно), апстрим публикует код с задержкой после бинарных релизов.

### Путь B — свой клиент на TDLib (как Monogram)
- **База:** [tdlib/td](https://github.com/tdlib/td) (лицензия Boost — свободнее GPL), UI с нуля (Kotlin/Compose).
- **Плюсы:** чистая архитектура, нет GPL-наследия от DrKLO, современный стек.
- **Минусы:** UI и все фичи (звонки, сторис, медиа-редактор…) пишем сами — годы работы; Monogram это только начинает.
- Для целей «потихоньку собрать рабочее приложение» — слишком долго.

**Рекомендация: Путь A** — форк DrKLO/Telegram, по образцу vpGram (обход) + CherryGram/AyuGram (фичи приватности).

---

## 6. Правила Telegram (обязательные)

Из [core.telegram.org/api/obtaining_api_id](https://core.telegram.org/api/obtaining_api_id) и README DrKLO:

1. **Получить свой api_id/api_hash** на my.telegram.org (нужен действующий Telegram-аккаунт; один api_id на номер). Sample-ключ из кода апстрима для релизов **запрещён** — пользователи получат `API_ID_PUBLISHED_FLOOD`.
2. **Не называть приложение «Telegram»** (или явно обозначить неофициальность) — наше имя Omni Message этому соответствует.
3. **Не использовать официальный логотип** (белый самолётик в синем круге) — нужен свой.
4. **Публиковать исходники** (GPL-2.0 унаследован от DrKLO; README Telegram прямо напоминает «publish your code too»).
5. Аккаунты, заходящие через неофициальные клиенты, автоматически «под наблюдением»; за спам/флуд — перманентный бан. Случайные баны снимают через recover@telegram.org.
6. Дополнительный риск: Telegram может отключить чужой api_id (аргумент экспертов CNews про «Телегу»: «форк зависит от API оригинального Telegram») — ещё одна причина не нарушать правила.

Лицензионная арифметика: форк GPL-2.0 → наш APK обязан сопровождаться исходниками; свои новые модули можем лицензировать как хотим, но собранный продукт — GPL-2.0. Монетизация возможна (донаты/спонсорство), реклама с SDK телеметрии — репутационно самоубийственна (см. разоблачение Graph/iMe).

---

## 7. План Omni Message

**Параметры:** пакет `com.naua_omni_message.app`, ABI `armeabi-v7a` + `arm64-v8a`, распространение — APK с GitHub/сайта (+ RuStore опционально).

### Этап 0 — подготовка
- Аккаунт my.telegram.org → api_id/api_hash.
- Пара ключей подписи (keystore), приватный GitHub-репо (позже публичный под GPL).
- Рабочая станция: Android Studio 2025.1.4, NDK 27.2.12479018, SDK 36, JDK. Клон `DrKLO/Telegram --recursive --shallow-submodules` (~несколько ГБ).

### Этап 1 — базовая сборка
- Собрать апстрим как есть (flavor `Afat`, debug) — проверка тулчейна.
- Заменить dummy-ключи: keystore, BuildVars.java (свой `APP_ID`/`APP_HASH`), google-services.json (или вырезать FCM-зависимость).

### Этап 2 — ребрендинг (минимальный, чтобы не ломать ребейзы)
- `applicationId` → `com.naua_omni_message.app` (+ `.beta` для беты) в `TMessagesProj/build.gradle`. Кодовый namespace `org.telegram.messenger` **оставить** — меньше конфликтов при ребейзах (стандартная практика форков).
- Имя приложения, иконки, splash, свой логотип (не самолётик!), строковые ресурсы.
- В `TMessagesProj/build.gradle` ограничить ABI: `ndk.abiFilters += ["armeabi-v7a", "arm64-v8a"]` / `splits.abi.include` — APK станет заметно легче (у апстрима 4 ABI + universal).

### Этап 3 — фичи первой очереди (по мотивам изученных клиентов)
- Ghost mode (локальное подавление прочтений/онлайна/typing) — рецепт AyuGram.
- Показ удалённых сообщений (локальный anti-recall).
- Скрытие номера/онлайна/просмотра сторис (CherryGram).
- Локальный премиум для клиентских фич.

### Этап 4 — работа в РФ из коробки
- Модуль авто-подключения: зашитый fallback MTProto-прокси + обновление списка прокси по HTTPS (GitHub raw / свой сайт), т.к. из заблокированного Telegram список не получить.
- Опционально (когда будет бюджет): собственные MTProto-proxy серверы вне РФ по образцу vpGram.
- Прозрачность: показать пользователю статус «обход включён» и что именно используется — прямое отличие от Telega, которая это скрывала.

### Этап 5 — релизы и поддержка
- Подписанные release-APK по двум ABI + universal на GitHub Releases.
- Регулярные ребейзы на апстрим DrKLO (security) — лучше маленькими частыми порциями.

### Риски
- **Зависимость от api_id**: соблюдать ToS, иначе Telegram отключит клиент целиком.
- **GPL**: публиковать исходники каждого распространяемого APK.
- **Инфраструктура прокси**: публичные списки ненадёжны; свои серверы = деньги и поддержка; серверы вне юрисдикции РФ.
- **Поддержка armv7**: часть зависимостей апстрима может требовать внимания под 32-бит (собирается, но тестировать отдельно).
- my.telegram.org из РФ может не открываться без VPN (блокировка домена) — подготовить доступ заранее.

---

## 8. Краткая сводка источников

- AyuGram: [GitHub](https://github.com/ayugram) · [AyuGram4A](https://github.com/AyuGram/AyuGram4A) · [Desktop](https://github.com/AyuGram/AyuGramDesktop)
- exteraGram: [GitHub](https://github.com/exteraSquad/exteraGram) · [сайт](https://exteragram.app/) · [SmartGram](https://github.com/smarts-uz/SmartGram)
- CherryGram: [GitHub](https://github.com/arsLan4k1390/Cherrygram) · [зеркало](https://github.com/Qing0721/Cherrygram)
- vpGram: [GitHub](https://github.com/vpgram/vpgram-android) · [сайт](https://vpgram.website) · [Google Play](https://play.google.com/store/apps/details?id=click.vpgram.messenger)
- Базовый апстрим: [DrKLO/Telegram](https://github.com/DrKLO/Telegram) · [правила api_id](https://core.telegram.org/api/obtaining_api_id)
- Блокировка в РФ: [Википедия](https://ru.wikipedia.org/wiki/Блокировка_Telegram_в_России_(2026)) · [CNews](https://www.cnews.ru/news/top/2026-03-12_pozvolyayushchij_obhodit_blokirovku) · [AppleInsider](https://appleinsider.ru/tips-tricks/blokirovka-telegram-v-rossii-chto-proishodit-v-aprele-2026.html)
- Безопасность клиентов: [RKS Global](https://rks.global/ru/research/alt-telegram-clients/) · [Habr/Monogram](https://habr.com/ru/news/1016316/)
- Обзоры: [vc.ru](https://vc.ru/telegram/1831259-9-storonnih-klientov-telegram-dlya-android-vo-vsem-luchshe-originalnogo) · [4PDA Telegraph](https://4pda.to/forum/index.php?showtopic=839842) · [обходы 2026](https://digirpt.com/2026/02/11/vse-sposoby-obojti-zamedlenie-telegram-v-rossii-v-2026-godu/)
