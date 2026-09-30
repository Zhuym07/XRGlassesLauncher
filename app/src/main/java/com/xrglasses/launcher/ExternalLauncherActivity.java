package com.xrglasses.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class ExternalLauncherActivity extends Activity {
    private final SessionState state = new SessionState();
    private DisplaySession displays;
    private ExternalTaskLauncher launcher;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        displays = new DisplaySession(this, state);
        displays.discoverExternalDisplay();
        Display display = getWindowManager().getDefaultDisplay();
        if (display == null || display.getDisplayId() <= 0) {
            getSharedPreferences("xr_session", MODE_PRIVATE).edit().putString("launch_status",
                    "External launcher was redirected to phone display by the system").apply();
            finish();
            return;
        }
        state.displayId = display.getDisplayId();
        final int ownDisplayId = state.displayId;
        displays.setListener(() -> {
            if (state.displayId != ownDisplayId) finish();
        });
        launcher = new ExternalTaskLauncher(this, state);
        launcher.setListener(() -> { if (status != null) status.setText(state.lastError); });
        showApps();
    }

    private void showApps() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(12), dp(24), dp(12));
        page.setBackgroundColor(Color.rgb(20, 24, 28));
        TextView title = new TextView(this);
        title.setText("XR Desktop  |  Display " + state.displayId);
        title.setTextSize(22); title.setTextColor(Color.WHITE);
        page.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.LTGRAY); status.setTextSize(14);
        status.setText(state.lastError.isEmpty() ? state.displayWidth + " x " + state.displayHeight
                : state.lastError);
        status.setMinHeight(dp(44));
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        GridLayout grid = new GridLayout(this);
        int columns = Math.max(2, Math.min(6, Math.round(state.displayWidth
                / getResources().getDisplayMetrics().density / 180)));
        grid.setColumnCount(columns);
        scroll.addView(grid);
        int index = 0;
        for (AppCatalog.Entry app : new AppCatalog().load(this)) {
            Button item = new Button(this);
            item.setText(app.label); item.setAllCaps(false);
            item.setTextSize(14); item.setMaxLines(2);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            app.icon.setBounds(0, 0, dp(36), dp(36));
            item.setCompoundDrawables(null, app.icon, null, null);
            item.setCompoundDrawablePadding(dp(6));
            item.setOnClickListener(view -> launcher.launchPackage(app.packageName, app.className));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f));
            params.width = 0; params.height = dp(108);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(item, params);
            index++;
        }
        setContentView(page);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() {
        super.onResume();
        if (launcher != null) showApps();
    }
    @Override protected void onDestroy() {
        if (launcher != null) launcher.release();
        if (displays != null) displays.release();
        super.onDestroy();
    }
}
