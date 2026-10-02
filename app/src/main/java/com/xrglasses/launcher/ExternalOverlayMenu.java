package com.xrglasses.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A temporary application-overlay menu created in the external display's
 * display-aware WindowContext. It keeps the current external app visible
 * underneath while the user picks another app.
 */
public final class ExternalOverlayMenu {
    private static final String TAG = "XROverlay";

    public interface Listener {
        void onMessage(String message);
    }

    private final Context appContext;
    private final ExternalTaskLauncher launcher;
    private final LauncherSettingsStore settingsStore;

    private WindowManager windowManager;
    private Context windowContext;
    private View windowView;
    private LinearLayout list;
    private TextView status;
    private TextView title;
    private TextView subtitle;
    private int displayId = -1;
    private String selectedMode = "recent";
    private Listener listener;

    public ExternalOverlayMenu(Context context, ExternalTaskLauncher launcher,
                               LauncherSettingsStore settingsStore) {
        this.appContext = context.getApplicationContext();
        this.launcher = launcher;
        this.settingsStore = settingsStore;
    }

    public boolean isShowing() {
        return windowView != null;
    }

    public boolean show(int displayId, String mode, Listener listener) {
        hide();
        this.listener = listener;
        this.displayId = displayId;
        this.selectedMode = mode == null ? "recent" : mode;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            notifyMessage(t(R.string.overlay_unsupported));
            return false;
        }
        if (!Settings.canDrawOverlays(appContext)) {
            notifyMessage(t(R.string.overlay_denied));
            Log.w(TAG, "overlay permission denied display=" + displayId);
            return false;
        }

        Display display = appContext.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(displayId);
        if (display == null || !display.isValid() || displayId <= 0) {
            notifyMessage(t(R.string.overlay_invalid));
            Log.w(TAG, "invalid display=" + displayId);
            return false;
        }

        try {
            Context displayContext = AppLanguage.wrap(appContext.createDisplayContext(display));
            windowContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            if (windowContext.getDisplay() == null
                    || windowContext.getDisplay().getDisplayId() != displayId) {
                notifyMessage(t(R.string.overlay_wrong_display));
                Log.e(TAG, "window context display mismatch requested=" + displayId
                        + " actual=" + (windowContext.getDisplay() == null
                        ? -1 : windowContext.getDisplay().getDisplayId()));
                windowContext = null;
                return false;
            }
            windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                notifyMessage(t(R.string.overlay_service_unavailable));
                return false;
            }
            windowView = buildContent(windowContext);
            WindowManager.LayoutParams params = createLayoutParams(display);
            windowManager.addView(windowView, params);
            Display actualDisplay = windowView.getDisplay();
            if (actualDisplay == null || actualDisplay.getDisplayId() != displayId) {
                Log.e(TAG, "overlay attached to wrong display requested=" + displayId
                        + " actual=" + (actualDisplay == null ? -1 : actualDisplay.getDisplayId()));
                windowManager.removeViewImmediate(windowView);
                windowView = null;
                windowManager = null;
                windowContext = null;
                notifyMessage(t(R.string.overlay_on_phone));
                return false;
            }
            Log.i(TAG, "overlay shown display=" + displayId + " mode=" + selectedMode
                    + " contextDisplay=" + windowContext.getDisplay().getDisplayId());
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "external overlay failed display=" + displayId, error);
            windowView = null;
            windowManager = null;
            windowContext = null;
            notifyMessage(t(R.string.operation_failed, shortMessage(error)));
            return false;
        }
    }

    public void hide() {
        View current = windowView;
        WindowManager wm = windowManager;
        windowView = null;
        windowManager = null;
        windowContext = null;
        if (current != null && wm != null) {
            try {
                wm.removeViewImmediate(current);
            } catch (RuntimeException error) {
                Log.w(TAG, "remove overlay failed", error);
            }
        }
        listener = null;
        displayId = -1;
    }

    public void onDisplayDisconnected() {
        if (isShowing()) {
            Log.i(TAG, "display disconnected; hide overlay display=" + displayId);
            hide();
        }
    }

    private WindowManager.LayoutParams createLayoutParams(Display display) {
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        int width = Math.min(dp(windowContext, 860), Math.max(dp(windowContext, 560), (int) (size.x * 0.74f)));
        int height = Math.min(dp(windowContext, 700), Math.max(dp(windowContext, 430), (int) (size.y * 0.76f)));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        params.setTitle(t(R.string.overlay_title_window));
        return params;
    }

    private View buildContent(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 26), dp(context, 20), dp(context, 26), dp(context, 20));
        card.setBackground(roundBackground(Color.rgb(25, 30, 37), dp(context, 26)));
        card.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hide();
                return true;
            }
            return false;
        });

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.VERTICAL);
        title = text(context, t(R.string.recents), 24, Color.WHITE);
        subtitle = text(context, t(R.string.overlay_subtitle), 13, Color.LTGRAY);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        Button close = button(context, t(R.string.close));
        close.setOnClickListener(v -> hide());
        header.addView(close, new LinearLayout.LayoutParams(dp(context, 92), dp(context, 46)));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = text(context,
                t(R.string.overlay_hint_ui),
                13, Color.rgb(190, 196, 205));
        hint.setPadding(0, dp(context, 8), 0, dp(context, 12));
        card.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tab(context, t(R.string.recents), "recent"));
        tabs.addView(tab(context, t(R.string.favorites), "favorites"));
        tabs.addView(tab(context, t(R.string.all_apps), "all"));
        tabsScroll.addView(tabs, new HorizontalScrollView.LayoutParams(-1, -2));
        card.addView(tabsScroll, new LinearLayout.LayoutParams(-1, -2));

        status = text(context, "", 13, Color.rgb(190, 196, 205));
        status.setPadding(0, dp(context, 10), 0, dp(context, 10));
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        renderList();
        return card;
    }

    private Button tab(Context context, String label, String mode) {
        Button button = button(context, label);
        button.setOnClickListener(v -> {
            selectedMode = mode;
            renderList();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(context, 132), dp(context, 46));
        params.setMargins(0, 0, dp(context, 8), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void renderList() {
        if (list == null || windowContext == null) return;
        list.removeAllViews();
        List<Item> items = itemsFor(selectedMode);
        title.setText(titleFor(selectedMode));
        launcher.refreshLiveState();
        String runningPackage = launcher.getRunningPackage();
        subtitle.setText(runningPackage.isEmpty()
                ? t(R.string.choose_to_start)
                : t(R.string.background_app, launcher.getRunningLabel()));
        if (items.isEmpty()) {
            status.setText(emptyText(selectedMode));
            return;
        }
        status.setText(localizedContext().getResources().getQuantityString(R.plurals.apps_count, items.size(), items.size()));
        for (Item item : items) addItem(item, runningPackage);
    }

    private String titleFor(String mode) {
        if ("favorites".equals(mode)) return t(R.string.favorites);
        if ("all".equals(mode)) return t(R.string.all_apps);
        return t(R.string.recents);
    }

    private String emptyText(String mode) {
        if ("favorites".equals(mode)) return t(R.string.empty_favorites_menu);
        if ("recent".equals(mode)) return t(R.string.empty_recent);
        return t(R.string.empty_all);
    }

    private List<Item> itemsFor(String mode) {
        List<Item> result = new ArrayList<>();
        if ("recent".equals(mode)) {
            for (ActiveExternalApp app : launcher.getRecentApps(20)) {
                result.add(new Item(app.packageName, app.className, app.label,
                        resolveIcon(app.packageName), app.lastConfirmedAt));
            }
            return result;
        }
        Set<String> favorites = new HashSet<>(settingsStore.read().getFavoriteComponents());
        for (AppCatalog.Entry app : new AppCatalog().load(appContext)) {
            String key = app.packageName + "/" + app.className;
            if ("favorites".equals(mode) && !favorites.contains(key)) continue;
            result.add(new Item(app.packageName, app.className, app.label.toString(), app.icon, 0L));
        }
        Collections.sort(result, Comparator.comparing(item -> item.label.toLowerCase()));
        return result;
    }

    private android.graphics.drawable.Drawable resolveIcon(String packageName) {
        try {
            return appContext.getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private void addItem(Item item, String runningPackage) {
        Context context = windowContext;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        row.setBackground(roundBackground(Color.rgb(46, 54, 65), dp(context, 16)));

        ImageView icon = new ImageView(context);
        if (item.icon != null) icon.setImageDrawable(item.icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(context, 46), dp(context, 46));
        iconParams.rightMargin = dp(context, 14);
        row.addView(icon, iconParams);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(context, item.label, 17, Color.WHITE);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(label, new LinearLayout.LayoutParams(-1, -2));

        String secondary = item.lastConfirmedAt > 0
                ? t(R.string.used_at, formatTime(item.lastConfirmedAt))
                : item.packageName;
        TextView packageView = text(context, secondary, 12, Color.rgb(194, 200, 210));
        packageView.setMaxLines(1);
        packageView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(packageView, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!runningPackage.isEmpty() && runningPackage.equals(item.packageName)) {
            TextView badge = text(context, t(R.string.current), 12, Color.rgb(210, 235, 255));
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
            badge.setBackground(roundBackground(Color.rgb(37, 91, 132), dp(context, 20)));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
            badgeParams.leftMargin = dp(context, 10);
            row.addView(badge, badgeParams);
        }

        row.setOnClickListener(v -> {
            row.setEnabled(false);
            row.setAlpha(0.72f);
            status.setText(t(R.string.opening_app, item.label));
            boolean launched;
            try {
                launched = launcher.launchPackage(item.packageName, item.className);
            } catch (RuntimeException error) {
                launched = false;
                Log.e(TAG, "launch package failed " + item.packageName, error);
            }
            Listener callback = listener;
            if (launched) {
                if (callback != null) callback.onMessage(t(R.string.started_app, item.label));
                hide();
            } else {
                row.setEnabled(true);
                row.setAlpha(1f);
                String message = t(R.string.launch_failed_app, item.label);
                status.setText(message + "\n" + launcher.queryTaskPlacement());
                if (callback != null) callback.onMessage(message);
            }
        });
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(context, 74));
        rowParams.setMargins(0, dp(context, 8), 0, 0);
        list.addView(row, rowParams);
    }

    private String formatTime(long timestamp) {
        long age = Math.max(0L, System.currentTimeMillis() - timestamp);
        if (age < 24L * 60L * 60L * 1000L) {
            return DateFormat.getTimeInstance(DateFormat.SHORT, localizedContext().getResources().getConfiguration().getLocales().get(0)).format(new Date(timestamp));
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, localizedContext().getResources().getConfiguration().getLocales().get(0)).format(new Date(timestamp));
    }

    private Context localizedContext() {
        return windowContext != null ? windowContext : AppLanguage.wrap(appContext);
    }

    private String t(int resource, Object... args) {
        return localizedContext().getString(resource, args);
    }

    private void notifyMessage(String message) {
        if (listener != null && message != null) listener.onMessage(message);
    }

    private static TextView text(Context context, String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        return view;
    }

    private static Button button(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private static GradientDrawable roundBackground(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String shortMessage(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static final class Item {
        final String packageName;
        final String className;
        final String label;
        final android.graphics.drawable.Drawable icon;
        final long lastConfirmedAt;

        Item(String packageName, String className, String label,
             android.graphics.drawable.Drawable icon, long lastConfirmedAt) {
            this.packageName = packageName;
            this.className = className;
            this.label = label;
            this.icon = icon;
            this.lastConfirmedAt = lastConfirmedAt;
        }
    }
}
