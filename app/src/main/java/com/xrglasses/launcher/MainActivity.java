package com.xrglasses.launcher;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.List;

public class MainActivity extends Activity {
    private final SessionState state = new SessionState();
    private DisplaySession displays;
    private ExternalTaskLauncher launcher;
    private InputBridge input;
    private TextView status;
    private TouchpadView touchpad;
    private AlertDialog textPanel;
    private float originalBrightness;
    private int originalSystemUi;
    private int touchpadDisplay = -1;
    private boolean rootBackEnabled;
    private final OnBackInvokedCallback touchpadBack = this::leaveTouchpad;
    private final Runnable accessibilityConnection = this::refreshAccessibilityConnection;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        displays = new DisplaySession(this, state);
        input = new InputBridge(this, new RootBridge(), state);
        launcher = new ExternalTaskLauncher(this, state);
        launcher.setListener(this::refreshStatus);
        displays.setListener(() -> {
            if (state.touchpadMode && (state.displayId != touchpadDisplay
                    || state.displayStatus != SessionState.DisplayStatus.CONNECTED)) {
                leaveTouchpad();
                notice("External display changed or disconnected");
            }
            refreshStatus();
        });
        displays.discoverExternalDisplay();
        showPage();
    }

    @Override protected void onStart() {
        super.onStart();
        TextRelayAccessibilityService.addConnectionListener(accessibilityConnection);
    }

    @Override protected void onResume() {
        super.onResume();
        displays.discoverExternalDisplay();
        refreshAccessibilityConnection();
    }

    private void refreshAccessibilityConnection() {
        state.accessibilityEnabled = TextRelayAccessibilityService.isEnabled();
        if (state.touchpadMode && !state.accessibilityEnabled) {
            leaveTouchpad();
            notice("Accessibility disconnected; touchpad stopped");
        }
        refreshStatus();
    }

    private void showPage() {
        status = null;
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(28), dp(20), dp(28));
        page.setBackgroundColor(Color.rgb(20, 24, 28));
        scroll.addView(page);
        page.addView(label("XR Desktop", 24, Color.WHITE));
        status = label("", 14, Color.LTGRAY);
        status.setTextIsSelectable(true);
        page.addView(status);
        addButton(page, "Refresh display", () -> displays.discoverExternalDisplay());
        addButton(page, "Enable accessibility", () -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (RuntimeException error) { notice(error.toString()); }
        });
        addButton(page, "Open external launcher", () -> {
            displays.discoverExternalDisplay();
            launcher.launchLauncher();
            refreshStatus();
        });
        addButton(page, "Open app on external display", this::chooseApp);
        addButton(page, "Enter black touchpad", this::enterTouchpad);
        addButton(page, "Send text", this::textDialog);
        CheckBox rootBack = new CheckBox(this);
        rootBack.setText("Optional Root: external Back");
        rootBack.setTextColor(Color.LTGRAY);
        rootBack.setChecked(rootBackEnabled);
        rootBack.setOnCheckedChangeListener((view, checked) -> {
            rootBackEnabled = checked;
            input.setRootBackEnabled(checked);
        });
        page.addView(rootBack);
        addButton(page, "Diagnostics", () -> new AlertDialog.Builder(this)
                .setTitle("XR diagnostics")
                .setMessage(statusText() + "\n" + TextRelayAccessibilityService.diagnostics()
                        + "\n" + launcher.queryTaskPlacement())
                .setPositiveButton("OK", null).show());
        setContentView(scroll);
        refreshStatus();
    }

    private void chooseApp() {
        displays.discoverExternalDisplay();
        if (state.displayId <= 0) { notice("Connect the external display first"); return; }
        List<AppCatalog.Entry> apps = new AppCatalog().load(this);
        CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label;
        new AlertDialog.Builder(this).setTitle("External apps")
                .setItems(labels, (dialog, which) -> {
                    AppCatalog.Entry app = apps.get(which);
                    launcher.launchPackage(app.packageName, app.className);
                    refreshStatus();
                }).setNegativeButton("Cancel", null).show();
    }

    private void enterTouchpad() {
        if (state.touchpadMode) return;
        displays.discoverExternalDisplay();
        if (state.displayId <= 0) { notice("Connect the external display first"); return; }
        if (!TextRelayAccessibilityService.isEnabled()) {
            notice("Enable XR Desktop accessibility and return to this app"); return;
        }
        if (!input.start()) { notice(state.lastInputResult); refreshStatus(); return; }
        originalBrightness = getWindow().getAttributes().screenBrightness;
        originalSystemUi = getWindow().getDecorView().getSystemUiVisibility();
        touchpadDisplay = state.displayId;
        state.touchpadMode = true;
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, touchpadBack);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyTouchpadWindow();
        status = null;
        touchpad = new TouchpadView(this, input, state, this::textDialog);
        setContentView(touchpad);
    }

    private void applyTouchpadWindow() {
        setBrightness(0.01f);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void leaveTouchpad() {
        if (!state.touchpadMode) return;
        state.touchpadMode = false;
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(touchpadBack);
        }
        touchpadDisplay = -1;
        if (textPanel != null) textPanel.dismiss();
        if (touchpad != null) touchpad.cancel();
        touchpad = null;
        input.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setBrightness(originalBrightness);
        getWindow().getDecorView().setSystemUiVisibility(originalSystemUi);
        showPage();
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = value;
        getWindow().setAttributes(params);
    }

    private void textDialog() {
        if (textPanel != null) return;
        final int target = state.displayId;
        if (target <= 0) { notice("Connect the external display first"); return; }
        final EditText field = new EditText(this);
        field.setMinLines(2);
        field.setText(getSharedPreferences("xr_session", MODE_PRIVATE).getString("text_draft", ""));
        final boolean wasTouchpad = state.touchpadMode;
        final float previousBrightness = getWindow().getAttributes().screenBrightness;
        if (wasTouchpad) {
            touchpad.cancel();
            setBrightness(originalBrightness);
        }
        textPanel = new AlertDialog.Builder(this).setTitle("External text")
                .setView(field).setPositiveButton("Send", null)
                .setNegativeButton("Cancel", null).create();
        textPanel.setOnDismissListener(dialog -> {
            getSharedPreferences("xr_session", MODE_PRIVATE).edit()
                    .putString("text_draft", field.getText().toString()).apply();
            InputMethodManager ime = getSystemService(InputMethodManager.class);
            if (ime != null) ime.hideSoftInputFromWindow(field.getWindowToken(), 0);
            textPanel = null;
            if (wasTouchpad && state.touchpadMode) applyTouchpadWindow();
            else if (!wasTouchpad) setBrightness(previousBrightness);
        });
        textPanel.setOnShowListener(dialog -> textPanel.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (target != state.displayId || !input.sendText(target, field.getText().toString())) {
                        notice("No editable external focus. Text remains here."); return;
                    }
                    field.setText("");
                    textPanel.dismiss();
                }));
        textPanel.show();
        field.requestFocus();
        textPanel.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void refreshStatus() { if (status != null) status.setText(statusText()); }

    private String statusText() {
        return "Display: " + state.displayStatus + "  id=" + state.displayId
                + "  " + state.displayWidth + " x " + state.displayHeight
                + "\nAccessibility: " + TextRelayAccessibilityService.isEnabled()
                + "\nInput: " + state.inputBackend + "\n" + state.lastInputResult
                + "\n" + getSharedPreferences("xr_session", MODE_PRIVATE)
                        .getString("launch_status", state.lastError);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private void addButton(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private void notice(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (state.touchpadMode && textPanel == null
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.getRepeatCount() == 0) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) input.back(state.displayId);
                else input.home(state.displayId);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (state.touchpadMode && textPanel == null
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // Android 13+ uses the registered callback; retain hardware/older OS handling.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (state.touchpadMode) leaveTouchpad(); else super.onBackPressed();
    }

    @Override protected void onStop() {
        TextRelayAccessibilityService.removeConnectionListener(accessibilityConnection);
        leaveTouchpad();
        if (textPanel != null) textPanel.dismiss();
        super.onStop();
    }

    @Override protected void onDestroy() {
        leaveTouchpad();
        if (input != null) input.close();
        if (launcher != null) launcher.release();
        if (displays != null) displays.release();
        super.onDestroy();
    }
}
