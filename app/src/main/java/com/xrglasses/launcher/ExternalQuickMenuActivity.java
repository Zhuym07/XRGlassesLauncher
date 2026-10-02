package com.xrglasses.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Activity fallback for the external app menu.  The preferred path is now an
 * external-display TYPE_APPLICATION_OVERLAY; this Activity remains available
 * for ColorOS builds that reject display-aware overlay windows.
 */
public final class ExternalQuickMenuActivity extends LocalizedActivity {
    public static final String EXTRA_MODE = "mode";

    private final SessionState state = new SessionState();
    private ExternalTaskLauncher launcher;
    private LauncherSettingsStore settingsStore;
    private LinearLayout list;
    private TextView status;
    private String selectedMode = "recent";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        selectedMode = getIntent().getStringExtra(EXTRA_MODE);
        if (selectedMode == null) selectedMode = "recent";

        Display display = getWindowManager().getDefaultDisplay();
        if (display == null || display.getDisplayId() <= 0 || !display.isValid()) {
            finish();
            return;
        }
        state.displayId = display.getDisplayId();
        Point size = new Point();
        display.getRealSize(size);
        state.displayWidth = size.x;
        state.displayHeight = size.y;
        state.uniqueId = display.getName() + "|" + size.x + "x" + size.y;
        state.displayStatus = SessionState.DisplayStatus.CONNECTED;

        settingsStore = new LauncherSettingsStore(this);
        launcher = new ExternalTaskLauncher(this, state);
        setContentView(buildContent());
        renderList();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String mode = intent.getStringExtra(EXTRA_MODE);
        if (mode != null) selectedMode = mode;
        if (status != null) status.setText("");
        renderList();
    }

    @Override protected void onResume() {
        super.onResume();
        Display display = getWindowManager().getDefaultDisplay();
        if (display == null || display.getDisplayId() <= 0 || !display.isValid()) finish();
    }

    @Override protected void onDestroy() {
        if (launcher != null) launcher.release();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.dimAmount = 0.48f;
        window.setAttributes(attrs);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private View buildContent() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.rgb(16, 20, 25));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(20), dp(26), dp(20));
        card.setBackgroundColor(Color.rgb(28, 32, 38));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(dp(760), Math.max(dp(500), (int) (state.displayWidth * 0.78f))),
                Math.min(dp(620), Math.max(dp(360), (int) (state.displayHeight * 0.76f))));
        cardParams.gravity = Gravity.CENTER;
        overlay.addView(card, cardParams);

        TextView title = new TextView(this);
        title.setText(getString(R.string.quick_menu_title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.quick_menu_hint));
        hint.setTextColor(Color.LTGRAY);
        hint.setTextSize(13);
        hint.setPadding(0, dp(4), 0, dp(12));
        card.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tabButton(getString(R.string.recents), "recent"));
        tabs.addView(tabButton(getString(R.string.favorites), "favorites"));
        tabs.addView(tabButton(getString(R.string.all_apps), "all"));
        tabsScroll.addView(tabs, new HorizontalScrollView.LayoutParams(-1, -2));
        card.addView(tabsScroll, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(185, 210, 235));
        status.setTextSize(13);
        status.setMinHeight(dp(34));
        status.setPadding(0, dp(8), 0, dp(4));
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button close = new Button(this);
        close.setText(getString(R.string.close));
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(52));
        closeParams.topMargin = dp(10);
        card.addView(close, closeParams);
        return overlay;
    }

    private Button tabButton(String label, String mode) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            selectedMode = mode;
            renderList();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void renderList() {
        if (list == null || launcher == null) return;
        list.removeAllViews();
        List<Item> items = itemsFor(selectedMode);
        status.setText(items.isEmpty() ? emptyText(selectedMode) : (getResources().getQuantityString(R.plurals.apps_count, items.size(), items.size())));
        for (Item item : items) addItem(item);
    }

    private String emptyText(String mode) {
        if ("favorites".equals(mode)) return getString(R.string.empty_favorites_menu);
        if ("recent".equals(mode)) return getString(R.string.empty_recent);
        return getString(R.string.empty_all);
    }

    private List<Item> itemsFor(String mode) {
        List<Item> result = new ArrayList<>();
        if ("recent".equals(mode)) {
            for (ActiveExternalApp app : launcher.getRecentApps(20)) {
                result.add(new Item(app.packageName, app.className, app.label, null));
            }
            return result;
        }
        Set<String> favorites = new HashSet<>(settingsStore.read().getFavoriteComponents());
        for (AppCatalog.Entry app : new AppCatalog().load(this)) {
            String key = app.packageName + "/" + app.className;
            if ("favorites".equals(mode) && !favorites.contains(key)) continue;
            result.add(new Item(app.packageName, app.className, app.label.toString(), app.icon));
        }
        Collections.sort(result, Comparator.comparing(item -> item.label.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    private void addItem(Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackgroundColor(Color.rgb(40, 46, 54));

        ImageView icon = new ImageView(this);
        if (item.icon != null) icon.setImageDrawable(item.icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconParams.rightMargin = dp(14);
        row.addView(icon, iconParams);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(item.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(17);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(label, new LinearLayout.LayoutParams(-1, -2));
        TextView packageView = new TextView(this);
        packageView.setText(item.packageName);
        packageView.setTextColor(Color.LTGRAY);
        packageView.setTextSize(11);
        packageView.setMaxLines(1);
        packageView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(packageView, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        row.setOnClickListener(v -> {
            if (launcher.launchPackage(item.packageName, item.className)) {
                finish();
            } else {
                status.setText(getString(R.string.launch_failed_app, item.label) + "\n" + state.lastError);
            }
        });
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(70));
        rowParams.setMargins(0, dp(4), 0, 0);
        list.addView(row, rowParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Item {
        final String packageName;
        final String className;
        final String label;
        final android.graphics.drawable.Drawable icon;
        Item(String packageName, String className, String label, android.graphics.drawable.Drawable icon) {
            this.packageName = packageName;
            this.className = className;
            this.label = label;
            this.icon = icon;
        }
    }
}
