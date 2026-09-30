# Сборка Omni Message

Сборка идёт в **GitHub Actions** (`.github/workflows/build.yml`). Локальная сборка нужна только для отладки.

## Как запустить сборку

### Вариант 1 — GitHub Actions (основной)

1. **Settings → Secrets and variables → Actions → New repository secret**, создай 4 секрета:

| Секрет | Значение |
|---|---|
| `OMNI_KEYSTORE_BASE64` | base64 от release.keystore (см. ниже) |
| `RELEASE_STORE_PASSWORD` | пароль keystore |
| `RELEASE_KEY_ALIAS` | алиас ключа |
| `RELEASE_KEY_PASSWORD` | пароль ключа |

Получить base64 keystore:

```bash
base64 -w0 TMessagesProj/config/release.keystore
```

2. **Actions → Build APK → Run workflow**. Сборка ~40–90 минут (нативная часть собирается с нуля).
3. APK скачивается из раздела **Artifacts**.

Триггеры: `push` в `main`, `pull_request`, ручной запуск. Ручной запуск позволяет выбрать ABI и build type.

### Вариант 2 — локально (для отладки)

Требует: JDK 17, Android SDK 36 + build-tools 36.0.0 + cmake 3.22.1, NDK 27.2.12479018.

```bash
# 1. Подмодули (обязательно, ~4 ГБ, долго)
git submodule update --init --recursive

# 2. Конфиг сборки
cp gradle.properties.template gradle.properties
#   отредактируй RELEASE_* и APP_PACKAGE

# 3. Указать путь к SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 4. Собрать
./gradlew :TMessagesProj_App:assembleAfatDebug
```

APK: `TMessagesProj_App/build/outputs/apk/afat/*/app.apk`

## Windows: три обязательных условия

Сборка на Windows ломается на трёх вещах. Все три уже решены в этом репозитории,
но знать их нужно, если будешь переносить проект или настраивать машину с нуля.

**1. Путь проекта не должен содержать кириллицу.** Android Gradle Plugin проверяет путь и
падает с ошибкой:

```
Your project path contains non-ASCII characters. This will most likely cause the build to fail
on Windows. Please move your project to a different directory.
```

Не помогает ни VPN, ни `android.useAndroidX`. Варианты: ASCII-путь, либо junction
( junction — мгновенно, файлы остаются на месте):

```powershell
New-Item -ItemType Junction -Path C:\omni -Target "C:\путь\с\кириллицей\Omni Message"
cd C:\omni
.\gradlew.bat :TMessagesProj_App:assembleAfatDebug
```

**2. Нужен `gradlew.bat`.** Апстрим его не отслеживает, в репозитории лежит только Unix-скрипт.
PowerShell его не выполнит. Скопируй `gradlew.bat` из репозитория Gradle той же версии (8.13).

**3. Git и длинные пути.** Часть сабмодулей (в `media3` — тестовые дампы) даёт пути длиннее
260 символов и не распаковывается:

```
error: unable to create file .../sample_with_fake_auxiliary_tracks_...dump: Filename too long
```

```powershell
git config --global core.longpaths true
```

Если падает с `Filename too long` уже после распаковки — включи `LongPathsEnabled=1` в реестре
(`HKLM\SYSTEM\CurrentControlSet\Control\FileSystem`) и перезапусти git.

## Почему сборка такая долгая

Нативная часть (`TMessagesProj/jni`) — это C/C++ через CMake: ffmpeg, boringssl, libvpx, dav1d, libyuv,
wamr, tlottie. Это ~2-4 ГБ исходников и полная пересборка при каждом CI-запуске, если кеш холодный.

Что можно сделать для ускорения на следующих этапах:
- кеш `~/.gradle` и `~/.android/ndk` (в workflow уже частично есть)
- собирать один ABI за раз вместо четырёх
- для разработки — `assembleAfatDebug` вместо release

## Переменные сборки

| Свойство | Где | По умолчанию | Комментарий |
|---|---|---|---|
| `APP_PACKAGE` | gradle.properties | `org.telegram.messenger` | applicationId. Для релиза — свой |
| `APP_VERSION_CODE` | gradle.properties | `7105` | versionCode |
| `APP_VERSION_NAME` | gradle.properties | `12.10.5` | версия |
| `RELEASE_*` | gradle.properties / Secrets | — | подпись |
| `IS_PRIVATE` | gradle.properties | `false` | защита от сборки приватного кода публично |

## api_id / api_hash

Живут в `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java` (константы `APP_ID`, `APP_HASH`).
На сборку **не влияют** — читаются только в рантайме при авторизации. С дефолтными значениями APK
соберётся и запустится, но вход в Telegram не пройдёт.
