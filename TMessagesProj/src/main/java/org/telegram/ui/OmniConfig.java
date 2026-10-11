package org.telegram.ui;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;

/**
 * Omni Message — хранилище настроек (PLAN.md B6).
 *
 * Все тумблеры приватности живут здесь, в отдельном файле, чтобы правки
 * апстрима оставались минимальными: конфликтов при ребейзе (PLAN.md H4)
 * почти не будет, потому что меняется только этот класс.
 *
 * Миграции: версия SCHEMA обновляется при добавлении новых ключей, старые
 * значения сохраняются. Дефолты — «выключено»: ghost mode нельзя включать
 * принудительно, это ломает ожидания собеседников и палится.
 */
public class OmniConfig {

    private static final String PREFS = "omni_config";
    private static final int SCHEMA = 1;

    // --- Приватность (этап C) -------------------------------------------
    /** Не отправлять прочтения. Чаты помечаются прочитанными только локально. */
    public static boolean ghostRead = false;
    /** Не отправлять account.updateStatus при выходе из приложения. */
    public static boolean ghostOnline = false;
    /** Не отправлять typing-действия, включая «записывает голосовое». */
    public static boolean ghostTyping = false;
    /** Не удалять сообщения, а помечать «удалено у собеседника». */
    public static boolean antiRecall = false;

    // --- Обход блокировок (этап D) ---------------------------------------
    // proxyEnabled по умолчанию ВКЛ: «установил — работает» (PLAN.md D).
    // Без списка прокси включение ни на что не влияет — статус честно
    // показывает прямое соединение. Выключается тумблером навсегда.
    public static boolean proxyEnabled = true;
    public static boolean dnsOverHttps = false;
    /** Kill-switch: пока включён, модуль связи никогда сам не снимает прокси. */
    public static boolean proxyKillSwitch = false;

    // --- Оформление / безопасность (этапы C7, E) -------------------------
    public static boolean appLockEnabled = false;
    public static int schema = 0;

    // --- Ghost mode: разовый пропуск (не сохраняется) ----------------------
    // Нужен, чтобы внутренняя логика (или действие пользователя «прочитать
    // на сервере») могла один раз отправить настоящее прочтение, не выключая
    // ghostRead целиком. Счётчик, а не флаг: несколько запросов подряд —
    // несколько разрешений.
    private static int allowReadTokens = 0;

    public static synchronized void allowReadOnce() {
        allowReadTokens++;
    }

    public static synchronized boolean consumeAllowRead() {
        if (allowReadTokens > 0) {
            allowReadTokens--;
            return true;
        }
        return false;
    }

    private static SharedPreferences prefs;

    private static SharedPreferences getPrefs() {
        if (prefs == null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        }
        return prefs;
    }

    public static void load() {
        SharedPreferences p = getPrefs();
        schema = p.getInt("schema", 0);

        ghostRead = p.getBoolean("ghostRead", false);
        ghostOnline = p.getBoolean("ghostOnline", false);
        ghostTyping = p.getBoolean("ghostTyping", false);
        antiRecall = p.getBoolean("antiRecall", false);

        proxyEnabled = p.getBoolean("proxyEnabled", true);
        dnsOverHttps = p.getBoolean("dnsOverHttps", false);
        proxyKillSwitch = p.getBoolean("proxyKillSwitch", false);

        appLockEnabled = p.getBoolean("appLockEnabled", false);

        // Миграции: здесь добавляем перенос значений при изменении SCHEMA.
        // Пример (гипотетический):
        //   if (schema < 1) {
        //       ghostTyping = p.getBoolean("hideTyping", false); // старое имя
        //   }
    }

    public static void save() {
        SharedPreferences.Editor e = getPrefs().edit();
        e.putInt("schema", SCHEMA);
        e.putBoolean("ghostRead", ghostRead);
        e.putBoolean("ghostOnline", ghostOnline);
        e.putBoolean("ghostTyping", ghostTyping);
        e.putBoolean("antiRecall", antiRecall);
        e.putBoolean("proxyEnabled", proxyEnabled);
        e.putBoolean("dnsOverHttps", dnsOverHttps);
        e.putBoolean("proxyKillSwitch", proxyKillSwitch);
        e.putBoolean("appLockEnabled", appLockEnabled);
        e.apply();
    }
}
