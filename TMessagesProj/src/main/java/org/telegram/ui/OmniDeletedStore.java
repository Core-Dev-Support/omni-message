package org.telegram.ui;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Omni Message — архив удалённых собеседником сообщений (PLAN.md C2, Anti-recall v1).
 *
 * Как работает: MessagesController.processUpdateArray перед тем, как пометить
 * сообщения удалёнными, отдаёт их сюда. Мы сохраняем текст в кольцевой буфер
 * (SharedPreferences, последние 200), удаление идёт своим чередом.
 * Просмотр — раздел «Удалённые сообщения» в OmniSettingsActivity.
 *
 * Честные ограничения v1:
 *  - сохраняется только текст/подпись (медиа — пометкой [media]), без фото и файлов;
 *  - секретные чаты не перехватываются (там удаление идёт другим путём);
 *  - свои собственные удаления тоже попадают в архив — отфильтровать автора
 *    надёжно нельзя без хранения userId, а это уже лишние данные.
 */
public class OmniDeletedStore {

    private static final String PREFS = "omni_deleted";
    private static final int CAP = 200;
    private static final int MAX_TEXT = 500;

    public static class Entry {
        public long dialogId;
        public String title;
        public String text;
        public long date;
    }

    /** Вызывается из MessagesController.processUpdateArray (stageQueue). */
    public static void saveAll(int account, MessagesController controller, androidx.collection.LongSparseArray<ArrayList<Integer>> deletedMessages) {
        if (!OmniConfig.antiRecall || deletedMessages == null) {
            return;
        }
        try {
            for (int a = 0, size = deletedMessages.size(); a < size; a++) {
                long key = deletedMessages.keyAt(a);
                ArrayList<Integer> ids = deletedMessages.valueAt(a);
                if (ids == null || ids.isEmpty()) {
                    continue;
                }
                // key == 0: dialogId неизвестен (групповые апдейты без peer).
                // Такие пропускаем: без диалога запись бесполезна.
                if (key == 0) {
                    continue;
                }
                for (int msgId : ids) {
                    TLRPC.Message msg = null;
                    try {
                        // getMessagesStorage() в MessagesController protected,
                        // поэтому идём через публичный синглтон напрямую.
                        msg = MessagesStorage.getInstance(account).getMessage(key, msgId);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (msg == null) {
                        continue;
                    }
                    String text = extractText(msg);
                    if (TextUtils.isEmpty(text)) {
                        continue;
                    }
                    Entry e = new Entry();
                    e.dialogId = key;
                    e.title = resolveTitle(controller, key);
                    e.text = text;
                    e.date = msg.date;
                    append(e);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String extractText(TLRPC.Message msg) {
        String text = msg.message;
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(msg.caption)) {
            text = msg.caption;
        }
        if (TextUtils.isEmpty(text)) {
            if (msg.media != null && !(msg.media instanceof TLRPC.TL_messageMediaEmpty)) {
                text = "[media]";
            } else {
                return null;
            }
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT) + "…";
        }
        return text;
    }

    private static String resolveTitle(MessagesController controller, long dialogId) {
        try {
            if (DialogObject.isEncryptedDialog(dialogId)) {
                return "Secret chat";
            }
            if (dialogId > 0) {
                TLRPC.User user = controller.getUser(dialogId);
                if (user != null) {
                    return UserObject.getUserName(user);
                }
            } else {
                TLRPC.Chat chat = controller.getChat(-dialogId);
                if (chat != null) {
                    return chat.title;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "Chat " + dialogId;
    }

    private static synchronized void append(Entry e) {
        try {
            SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            JSONArray arr;
            try {
                arr = new JSONArray(p.getString("items", "[]"));
            } catch (Exception ex) {
                arr = new JSONArray();
            }
            JSONObject o = new JSONObject();
            o.put("d", e.dialogId);
            o.put("t", e.title);
            o.put("x", e.text);
            o.put("ts", e.date);
            arr.put(o);
            while (arr.length() > CAP) {
                JSONArray next = new JSONArray();
                for (int i = arr.length() - CAP; i < arr.length(); i++) {
                    next.put(arr.get(i));
                }
                arr = next;
            }
            p.edit().putString("items", arr.toString()).apply();
        } catch (Exception ex) {
            FileLog.e(ex);
        }
    }

    public static synchronized List<Entry> getRecent() {
        ArrayList<Entry> out = new ArrayList<>();
        try {
            SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString("items", "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.dialogId = o.optLong("d");
                e.title = o.optString("t");
                e.text = o.optString("x");
                e.date = o.optLong("ts");
                out.add(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    public static synchronized void clear() {
        try {
            ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().remove("items").apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
