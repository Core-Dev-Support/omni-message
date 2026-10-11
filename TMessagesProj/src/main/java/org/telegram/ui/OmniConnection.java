package org.telegram.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Omni Message — модуль связи для сетей с блокировками (PLAN.md D, v1).
 *
 * Раздача — только бесплатная (решено: платная инфраструктура слабая и
 * отдана под VPN). Поэтому из флоу Mirage взята только бесплатная часть:
 *   Mirage                                  Omni
 *   -------------------------------------   ----------------------------------
 *   app-config.json с зеркал (discovery)    omni/app-config.json → proxy_list_url
 *   free_panel_cache.json, TTL 10 мин       кеш prefs, TTL 6 ч (сервер слабый —
 *                                           реже дёргаем)
 *   захардкоженные fallback-серверы         bundledFallback()
 *   PingRepository перебор до CONNECTED     pingAndPick() через checkProxy
 *
 * Регистрации, токенов и серверного бэкенда НЕТ: список — обычный статический
 * JSON по HTTPS (gist / GitHub raw / любой хостинг), формат ниже. Обновляется
 * правкой файла, ничего деплоить не надо.
 *
 * Дальше своё: каждый кандидат пингуется, быстрейший включается штатным путём
 * (SharedConfig.currentProxy + setProxySettings) — весь трафик tgnet через него.
 * Сторож: висение в WaitingForNetwork дольше 15 c — ротация.
 * Kill-switch v1: модуль никогда сам не снимает прокси.
 *
 * Честные ограничения v1:
 *  - без URL списка (секрет OMNI_PROXY_URL или proxy_list_url в app-config.json)
 *    и без встроенного fallback включается нечего — тумблер честно пишет;
 *  - DoH/DoT (D4), подпись списка Ed25519 (D3), звонки через relay (D7) — позже.
 */
public class OmniConnection implements NotificationCenter.NotificationCenterDelegate {

    private static volatile OmniConnection instance;

    public static OmniConnection getInstance() {
        if (instance == null) {
            synchronized (OmniConnection.class) {
                if (instance == null) {
                    instance = new OmniConnection();
                }
            }
        }
        return instance;
    }

    public static class ProxyCandidate {
        public String host;
        public int port;
        public String secret;
        public long pingMs = -1;
    }

    private static final String PREFS = "omni_proxy";
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long ROTATE_AFTER_MS = 15_000L;
    private static final int PING_TIMEOUT_MS = 4000;

    private long waitingSince = 0;
    private String lastOmniProxyHost = null;
    private boolean started = false;
    // Опрос при старте — один раз, на первом событии соединения (натив уже
    // инициализирован; в onCreate дёргать сеть рано). Дальше — сторож.
    private boolean bootRefreshed = false;

    private OmniConnection() {
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didUpdateConnectionState);
        lastOmniProxyHost = prefs().getString("last_host", null);
        // Сам опрос — на первом событии соединения (см. ниже): сохранённый
        // прокси апстрим поднимает сам в ConnectionsManager.init(), а свежий
        // список подтянем, как только натив оживёт. В onCreate дёргать рано.
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didUpdateConnectionState) {
            return;
        }
        // При открытии приложения: опросить, взять ключи, вставить и подключить
        // самому, без участия человека. Сохранённый прокси уже поднят апстримом;
        // refreshAndEnable перепроверит список и переключит на быстрейший.
        // Не ломает рабочее состояние: при пустом/недоступном списке ничего не трогает.
        if (!bootRefreshed) {
            bootRefreshed = true;
            if (OmniConfig.proxyEnabled) {
                refreshAndEnable(null);
            }
            return;
        }
        if (!OmniConfig.proxyEnabled) {
            waitingSince = 0;
            return;
        }
        int state = ConnectionsManager.getInstance(account).getConnectionState();
        long now = System.currentTimeMillis();
        // Паритет NovaGram: триггер — висящий Connecting ИЛИ WaitingForNetwork.
        // Защита от ложных срабатываний — непрерывность >15 c: штатный коннект
        // проходит эти состояния за секунды и сбрасывает таймер.
        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork
                || state == ConnectionsManager.ConnectionStateConnecting) {
            if (waitingSince == 0) {
                waitingSince = now;
                return;
            }
            if (now - waitingSince > ROTATE_AFTER_MS) {
                waitingSince = now;
                rotate();
            }
        } else {
            waitingSince = 0;
        }
    }

    /** Включить/выключить автопрокси из настроек. Вызывать с UI-потока. */
    public void setAutoProxyEnabled(boolean enabled) {
        OmniConfig.proxyEnabled = enabled;
        OmniConfig.save();
        if (enabled) {
            refreshAndEnable(null);
        } else if (!OmniConfig.proxyKillSwitch) {
            // Ручное выключение — снимаем только наш прокси, чужой не трогаем.
            disableOwnProxy();
        }
    }

    /** Статус для строки в настройках. Вызывать с UI-потока. */
    public String getStatusText(android.content.Context ctx) {
        int account = UserConfig.selectedAccount;
        int state;
        try {
            state = ConnectionsManager.getInstance(account).getConnectionState();
        } catch (Exception e) {
            return ctx.getString(R.string.OmniNetworkOff);
        }
        boolean proxyOn = MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)
                && SharedConfig.currentProxy != null;
        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork
                || state == ConnectionsManager.ConnectionStateConnecting) {
            return ctx.getString(R.string.OmniNetworkConnecting);
        }
        if (proxyOn) {
            String host = SharedConfig.currentProxy.settings != null
                    ? SharedConfig.currentProxy.settings.getAddress() : "?";
            return ctx.getString(R.string.OmniNetworkProxyOn) + ": " + host;
        }
        if (state == ConnectionsManager.ConnectionStateConnected
                || state == ConnectionsManager.ConnectionStateUpdating) {
            return ctx.getString(R.string.OmniNetworkDirect);
        }
        return ctx.getString(R.string.OmniNetworkOff);
    }

    public interface Callback {
        void run(boolean ok, String message);
    }

    /** Обновить список и включить лучший. Фон, колбэк — на UI-поток. */
    public void refreshAndEnable(Callback cb) {
        new Thread(() -> {
            List<ProxyCandidate> list = fetchList();
            if (list.isEmpty()) {
                post(cb, false, "empty");
                return;
            }
            ProxyCandidate best = pingAndPick(list);
            if (best == null) {
                post(cb, false, "unreachable");
                return;
            }
            applyProxy(best);
            post(cb, true, best.host);
        }, "omni-proxy").start();
    }

    private void post(Callback cb, boolean ok, String message) {
        if (cb == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> cb.run(ok, message));
    }

    private void rotate() {
        new Thread(() -> {
            List<ProxyCandidate> list = cachedList();
            if (list.size() < 2) {
                list = fetchList();
            }
            list = cap(list);
            if (list.isEmpty()) {
                return;
            }
            // Следующий после текущего, по кругу.
            int start = 0;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).host.equals(lastOmniProxyHost)) {
                    start = (i + 1) % list.size();
                    break;
                }
            }
            for (int k = 0; k < list.size(); k++) {
                ProxyCandidate c = list.get((start + k) % list.size());
                if (ping(c)) {
                    applyProxy(c);
                    return;
                }
                // Паритет NovaGram: мёртвые удаляются из клиента — только то,
                // что реально работает. Чужие ручные прокси не трогаем.
                removeManaged(c.host);
            }
        }, "omni-proxy-rotate").start();
    }

    /** Потолок кандидатов за цикл (паритет NovaGram: тянем аккуратно, без лагов). */
    private static final int MAX_CANDIDATES = 15;

    private static List<ProxyCandidate> cap(List<ProxyCandidate> list) {
        if (list.size() <= MAX_CANDIDATES) {
            return list;
        }
        return new ArrayList<>(list.subList(0, MAX_CANDIDATES));
    }

    // --- Учёт своих прокси: удаляем только то, что сами добавили -------------

    private java.util.Set<String> managedHosts() {
        try {
            java.util.Set<String> s = prefs().getStringSet("managed_hosts", null);
            return s != null ? new java.util.HashSet<>(s) : new java.util.HashSet<>();
        } catch (Exception e) {
            return new java.util.HashSet<>();
        }
    }

    private void markManaged(String host) {
        try {
            java.util.Set<String> s = managedHosts();
            if (s.add(host)) {
                prefs().edit().putStringSet("managed_hosts", s).apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void removeManaged(String host) {
        try {
            java.util.Set<String> s = managedHosts();
            if (!s.remove(host)) {
                return; // чужой ручной прокси — не трогаем
            }
            prefs().edit().putStringSet("managed_hosts", s).apply();
            boolean changed = false;
            for (int i = SharedConfig.proxyList.size() - 1; i >= 0; i--) {
                SharedConfig.ProxyInfo p = SharedConfig.proxyList.get(i);
                if (p != null && p.settings != null && host.equals(p.settings.getAddress())
                        && p.settings.getType() == ProxySettings.Type.MTPROTO) {
                    SharedConfig.proxyList.remove(i);
                    changed = true;
                }
            }
            if (SharedConfig.currentProxy != null && SharedConfig.currentProxy.settings != null
                    && host.equals(SharedConfig.currentProxy.settings.getAddress())) {
                SharedConfig.currentProxy = null;
            }
            if (changed) {
                SharedConfig.saveProxyList();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void disableOwnProxy() {
        try {
            if (SharedConfig.currentProxy != null && SharedConfig.currentProxy.settings != null
                    && lastOmniProxyHost != null
                    && lastOmniProxyHost.equals(SharedConfig.currentProxy.settings.getAddress())) {
                ConnectionsManager.setProxySettings(false, null);
                SharedConfig.currentProxy = null;
                SharedConfig.saveProxyList();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                lastOmniProxyHost = null;
                prefs().edit().remove("last_host").apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void applyProxy(ProxyCandidate c) {
        try {
            ProxySettings settings = ProxySettings.builder()
                    .setType(ProxySettings.Type.MTPROTO)
                    .setAddress(c.host)
                    .setPort(c.port)
                    .setSecret(c.secret)
                    .build();
            if (!settings.isValid()) {
                return;
            }
            SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(settings);
            // Не плодим дубликаты нашего же хоста.
            for (int i = SharedConfig.proxyList.size() - 1; i >= 0; i--) {
                SharedConfig.ProxyInfo p = SharedConfig.proxyList.get(i);
                if (p != null && p.settings != null && c.host.equals(p.settings.getAddress())
                        && p.settings.getType() == ProxySettings.Type.MTPROTO) {
                    SharedConfig.proxyList.remove(i);
                }
            }
            SharedConfig.proxyList.add(0, info);
            SharedConfig.currentProxy = info;
            SharedConfig.saveProxyList();
            // Тот же порядок, что в ProxyListActivity: сначала свои ключи,
            // потом commit одним редактором.
            android.content.SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", true);
            settings.toSharedPreferences(editor);
            editor.apply();
            ConnectionsManager.setProxySettings(true, settings);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            lastOmniProxyHost = c.host;
            markManaged(c.host);
            prefs().edit().putString("last_host", c.host).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // --- Список: сеть → кеш → встроенный fallback ---------------------------

    /**
     * Встроенный fallback (PLAN.md D2): публичные MTProto-прокси (проверены,
     * свежие на момент вшивания) + место под свои.
     * Честно: это чужие серверы — оператор видит IP и факт подключения
     * (содержимое переписки MTProto-шифрованием закрыто). Поэтому: список
     * только стартовый, свои mtg — в приоритет (кладутся первыми и в
     * OMNI_PROXY_URL/app-config). Мёртвые отсекает пинг автоматически.
     */
    static List<ProxyCandidate> bundledFallback() {
        String[][] raw = {
                // Свои — всегда первые (пример формата):
                // {"proxy1.example.com", "443", "ee00000000000000000000000000000000"},
                // Публичные (источник: mtpro.xyz, свежие; ротация и пинг сами отсеют мёртвые):
                {"mtproto.online", "443", "ee139e0ee36150c1ea3bf299796586b5457777772e7674622e7275"},
                {"91.108.242.229", "443", "ee06471735f9009107bc26dd39fb0d95006f7a6f6e2e7275"},
                {"13.141.64.54", "443", "ee94989d74ad055391384d92c3c7378d264726976652e676f6f676c652e636f6d"},
                {"79.182.27.247", "443", "eeee835a34516912f161a007339be3c64676174665761792e6c2f6e79652d79652e6174"},
                {"nnmby.com", "443", "dd9e1dde0de02a2e7c22d10e2fff84101013"},
        };
        ArrayList<ProxyCandidate> out = new ArrayList<>();
        for (String[] r : raw) {
            if (r.length < 3) {
                continue;
            }
            ProxyCandidate c = new ProxyCandidate();
            c.host = r[0];
            try {
                c.port = Integer.parseInt(r[1]);
            } catch (Exception e) {
                continue;
            }
            c.secret = r[2];
            if (!c.host.isEmpty() && c.port > 0 && c.port < 65536 && !c.secret.isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    private List<ProxyCandidate> fetchList() {
        String url = resolveListUrl();
        if (url.isEmpty()) {
            // URL нет: только встроенный fallback и кеш.
            List<ProxyCandidate> bundled = bundledFallback();
            return bundled.isEmpty() ? cachedList() : bundled;
        }
        try {
            String resp = httpGet(url);
            // Сначала JSON {proxies:[...]}, иначе — tg://proxy-ссылки в тексте.
            // Так источником может быть что угодно: gist, страница, пост.
            List<ProxyCandidate> list;
            try {
                list = parseList(new JSONObject(resp));
            } catch (Exception je) {
                list = new ArrayList<>();
            }
            if (list.isEmpty()) {
                list = parseTgLinks(resp);
            }
            if (!list.isEmpty()) {
                cacheMerged(list);
            }
            return list.isEmpty() ? cachedList() : list;
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("omni proxy fetch failed: " + e);
            }
            return cachedList();
        }
    }

    // --- Discovery: откуда брать URL списка ----------------------------------
    // 1. OMNI_PROXY_URL (Secrets/local.properties) — прямой override.
    // 2. Кеш найденного URL (сутки).
    // 3. omni/app-config.json с зеркал → поле proxy_list_url.
    private static final String[] CONFIG_MIRRORS = {
            "https://raw.githubusercontent.com/Core-Dev-Support/omni-message/main/omni/app-config.json"
    };
    private static final long API_BASE_TTL_MS = 24 * 60 * 60 * 1000L;

    private String resolveListUrl() {
        String direct = proxyListUrl();
        if (direct != null && !direct.isEmpty()) {
            return stripSlash(direct);
        }
        String cached = prefs().getString("list_url", "");
        long at = prefs().getLong("list_url_at", 0);
        if (!cached.isEmpty() && System.currentTimeMillis() - at < API_BASE_TTL_MS) {
            return cached;
        }
        for (String mirror : CONFIG_MIRRORS) {
            try {
                String body = httpGet(mirror);
                String found = new JSONObject(body).optString("proxy_list_url", "").trim();
                if (!found.isEmpty()) {
                    found = stripSlash(found);
                    prefs().edit().putString("list_url", found).putLong("list_url_at", System.currentTimeMillis()).apply();
                    return found;
                }
            } catch (Exception e) {
                // следующее зеркало
            }
        }
        return "";
    }

    private static String stripSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private List<ProxyCandidate> parseList(JSONObject root) {
        ArrayList<ProxyCandidate> out = new ArrayList<>();
        try {
            JSONArray arr = root.optJSONArray("proxies");
            if (arr == null && root.has("config")) {
                arr = root.optJSONObject("config").optJSONArray("proxies");
            }
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                ProxyCandidate c = new ProxyCandidate();
                c.host = o.optString("host", "");
                c.port = o.optInt("port", 443);
                c.secret = o.optString("secret", "");
                if (!c.host.isEmpty() && c.port > 0 && c.port < 65536 && !c.secret.isEmpty()) {
                    out.add(c);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    /**
     * Парсинг tg://proxy-ссылок из любого текста (gist, страница, пост канала).
     * Формат стабилен годами: tg://proxy?server=HOST&port=PORT&secret=SECRET.
     * Позволяет держать список где угодно без JSON-обёртки.
     */
    static List<ProxyCandidate> parseTgLinks(String text) {
        ArrayList<ProxyCandidate> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "tg://proxy\\?server=([^&\\s\"'<>]+)&port=(\\d+)&secret=([0-9a-fA-F]+)"
            ).matcher(text);
            while (m.find()) {
                ProxyCandidate c = new ProxyCandidate();
                c.host = m.group(1);
                try {
                    c.port = Integer.parseInt(m.group(2));
                } catch (Exception e) {
                    continue;
                }
                c.secret = m.group(3);
                if (!c.host.isEmpty() && c.port > 0 && c.port < 65536 && !c.secret.isEmpty()) {
                    out.add(c);
                }
                if (out.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    private List<ProxyCandidate> cachedList() {
        ArrayList<ProxyCandidate> out = new ArrayList<>();
        try {
            android.content.SharedPreferences p = prefs();
            long at = p.getLong("fetched_at", 0);
            if (at == 0 || System.currentTimeMillis() - at > CACHE_TTL_MS) {
                return out;
            }
            out.addAll(parseList(new JSONObject(p.getString("raw", "{}"))));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    private void cacheMerged(List<ProxyCandidate> list) {
        try {
            // Храним нормализованный JSON: кеш всегда читается через parseList.
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (ProxyCandidate c : list) {
                JSONObject o = new JSONObject();
                o.put("host", c.host);
                o.put("port", c.port);
                o.put("secret", c.secret);
                arr.put(o);
            }
            root.put("proxies", arr);
            prefs().edit().putString("raw", root.toString()).putLong("fetched_at", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // --- Пинг ----------------------------------------------------------------

    private ProxyCandidate pingAndPick(List<ProxyCandidate> list) {
        ProxyCandidate best = null;
        for (ProxyCandidate c : cap(list)) {
            if (ping(c) && (best == null || c.pingMs < best.pingMs)) {
                best = c;
            }
        }
        return best;
    }

    private boolean ping(ProxyCandidate c) {
        try {
            ProxySettings settings = ProxySettings.builder()
                    .setType(ProxySettings.Type.MTPROTO)
                    .setAddress(c.host)
                    .setPort(c.port)
                    .setSecret(c.secret)
                    .build();
            if (!settings.isValid()) {
                return false;
            }
            int account = UserConfig.selectedAccount;
            CountDownLatch latch = new CountDownLatch(1);
            long[] time = new long[]{-1};
            long t0 = System.currentTimeMillis();
            ConnectionsManager.getInstance(account).checkProxy(settings, t -> {
                time[0] = t;
                latch.countDown();
            });
            boolean done = latch.await(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (done && time[0] >= 0) {
                c.pingMs = System.currentTimeMillis() - t0;
                return true;
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("omni proxy ping failed: " + c.host + " " + e);
            }
        }
        return false;
    }

    // --- Утилиты ---------------------------------------------------------------

    // UA как у обычного браузера (паттерн Mirage): меньше шансов, что CDN/WAF
    // порежет запросы как «ботовые».
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        if (conn.getResponseCode() != 200) {
            throw new Exception("HTTP " + conn.getResponseCode());
        }
        return readAll(conn.getInputStream());
    }

    private String proxyListUrl() {
        try {
            return org.telegram.messenger.BuildConfig.PROXY_URL;
        } catch (Exception e) {
            return "";
        }
    }

    private android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > 256 * 1024) {
                break;
            }
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
