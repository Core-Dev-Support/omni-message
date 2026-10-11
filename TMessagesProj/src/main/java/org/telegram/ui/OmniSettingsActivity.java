package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * Omni Message — корневой экран настроек (PLAN.md B6).
 *
 * Единая точка входа для всех наших фич, по образцу AyuGram / CherryGram:
 * один пункт в меню «Настройки», внутри — категории. Каждая категория —
 * отдельная фича, реализуемая независимо, поэтому разносим их по id:
 *   1xx — Приватность
 *   2xx — Обход блокировок
 *   3xx — Оформление
 *   4xx — Фишки
 *
 * Тумблеры хранятся в OmniConfig (SharedPreferences) и по умолчанию
 * выключены: ghost mode ломает ожидания собеседников, включать его
 * принудительно нельзя.
 */
public class OmniSettingsActivity extends BaseFragment {

    public static final int ID_PRIVACY = 100;
    public static final int ID_GHOST_READ = 101;
    public static final int ID_GHOST_ONLINE = 102;
    public static final int ID_GHOST_TYPING = 103;
    public static final int ID_ANTI_RECALL = 104;
    public static final int ID_DELETED_CLEAR = 105;
    public static final int ID_NETWORK = 200;
    public static final int ID_KILL_SWITCH = 201;
    public static final int ID_APP_LOCK = 300;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setTitle(getString(R.string.OmniSettingsTitle));

        FrameLayout contentView = new FrameLayout(context);

        // onClick — Utilities.Callback5<UItem, View, Integer, Float, Float>,
        // он void, поэтому возвращать из лямбды нечего (в отличие от
        // onLongClick, который Callback5Return и требует boolean).
        listView = new UniversalRecyclerView(this, this::fillItems, (item, view, position, x, y) -> onItemClick(item), null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);

        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(getString(R.string.OmniSettingsAbout)));

        // --- Приватность -------------------------------------------------
        items.add(UItem.asHeader(getString(R.string.OmniCategoryPrivacy)));
        items.add(UItem.asCheck(ID_GHOST_READ, getString(R.string.OmniGhostRead)).setChecked(OmniConfig.ghostRead));
        items.add(UItem.asCheck(ID_GHOST_ONLINE, getString(R.string.OmniGhostOnline)).setChecked(OmniConfig.ghostOnline));
        items.add(UItem.asCheck(ID_GHOST_TYPING, getString(R.string.OmniGhostTyping)).setChecked(OmniConfig.ghostTyping));
        items.add(UItem.asCheck(ID_ANTI_RECALL, getString(R.string.OmniAntiRecall)).setChecked(OmniConfig.antiRecall));
        items.add(UItem.asShadow(getString(R.string.OmniPrivacyWarning)));

        // --- Удалённые собеседником (Anti-recall, PLAN.md C2) ----------------
        // Только когда тумблер включён и архив не пуст. Строки read-only:
        // «имя • дата» + текст. Полный текст — в строке, она многострочная.
        java.util.List<OmniDeletedStore.Entry> deleted = OmniConfig.antiRecall
                ? OmniDeletedStore.getRecent() : null;
        if (deleted != null && !deleted.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.OmniDeletedTitle)));
            int shown = 0;
            for (OmniDeletedStore.Entry e : deleted) {
                if (shown >= 30) {
                    break;
                }
                items.add(UItem.asShadow(e.title + "\n" + e.text));
                shown++;
            }
            items.add(UItem.asButton(ID_DELETED_CLEAR, getString(R.string.OmniDeletedClear)));
            items.add(UItem.asShadow(getString(R.string.OmniDeletedInfo)));
        }

        // --- Обход блокировок (PLAN.md D, omni-connection) -------------------
        items.add(UItem.asHeader(getString(R.string.OmniCategoryNetwork)));
        items.add(UItem.asCheck(ID_NETWORK, getString(R.string.OmniNetworkTitle)).setChecked(OmniConfig.proxyEnabled));
        items.add(UItem.asCheck(ID_KILL_SWITCH, getString(R.string.OmniKillSwitch)).setChecked(OmniConfig.proxyKillSwitch));
        String netStatus = "";
        try {
            if (getParentActivity() != null) {
                netStatus = OmniConnection.getInstance().getStatusText(getParentActivity());
            }
        } catch (Exception e) {
            netStatus = "";
        }
        items.add(UItem.asShadow(getString(R.string.OmniNetworkInfo) + (netStatus.isEmpty() ? "" : "\n" + netStatus)));

        // --- Оформление ----------------------------------------------------
        items.add(UItem.asHeader(getString(R.string.OmniCategoryAppearance)));
        items.add(UItem.asCheck(ID_APP_LOCK, getString(R.string.OmniAppLock)).setChecked(OmniConfig.appLockEnabled));
        items.add(UItem.asShadow(getString(R.string.OmniAppearanceInfo)));
    }

    private void onItemClick(UItem item) {
        if (item == null || item.id == 0) {
            return;
        }
        switch (item.id) {
            case ID_GHOST_READ:
                OmniConfig.ghostRead = !OmniConfig.ghostRead;
                break;
            case ID_GHOST_ONLINE:
                OmniConfig.ghostOnline = !OmniConfig.ghostOnline;
                break;
            case ID_GHOST_TYPING:
                OmniConfig.ghostTyping = !OmniConfig.ghostTyping;
                break;
            case ID_ANTI_RECALL:
                OmniConfig.antiRecall = !OmniConfig.antiRecall;
                break;
            case ID_DELETED_CLEAR:
                OmniDeletedStore.clear();
                break;
            case ID_APP_LOCK:
                OmniConfig.appLockEnabled = !OmniConfig.appLockEnabled;
                break;
            case ID_NETWORK:
                // Включаем сторож: fetch списка → пинг → лучший прокси.
                // Сама работа в фоне (OmniConnection), экран только обновится.
                OmniConnection.getInstance().setAutoProxyEnabled(!OmniConfig.proxyEnabled);
                break;
            case ID_KILL_SWITCH:
                OmniConfig.proxyKillSwitch = !OmniConfig.proxyKillSwitch;
                break;
            default:
                return;
        }
        OmniConfig.save();
        updateItems();
    }

    private void updateItems() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        OmniConfig.load();
        updateItems();
    }
}
