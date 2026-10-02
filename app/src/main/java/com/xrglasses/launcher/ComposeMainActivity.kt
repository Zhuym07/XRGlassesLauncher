package com.xrglasses.launcher

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog as ComposeAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextAlign

class ComposeMainActivity : LocalizedComponentActivity() {
    private companion object {
        const val TOUCHPAD_PREFS = "xr_touchpad_session"
        const val KEY_ACTIVE = "active"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_UI = "ui"
    }
    private val session = SessionState()
    private lateinit var displays: DisplaySession
    private lateinit var launcher: ExternalTaskLauncher
    private lateinit var input: InputBridge
    private lateinit var settingsStore: LauncherSettingsStore
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var originalUi = 0
    private var touchpadDisplay = -1
    private var touchpadView: TouchpadView? = null
    private var externalOverlayMenu: ExternalOverlayMenu? = null
    private var touchpad by mutableStateOf(false)
    private val accessibilityConnection = Runnable {
        if (touchpad) {
            if (session.displayId != touchpadDisplay ||
                session.displayStatus != SessionState.DisplayStatus.CONNECTED) {
                scheduleTouchpadHealthCheck(getString(R.string.display_interrupted), 900L)
            } else if (!TextRelayAccessibilityService.isEnabled()) {
                scheduleTouchpadHealthCheck(getString(R.string.accessibility_unavailable), 900L)
            } else cancelTouchpadHealthCheck()
        }
        scheduleDesktopEvaluation()
        refreshUi()
    }
    private var refresh: (() -> Unit)? = null
    private var autoLauncherAttempted = false
    private var foreground = false
    private val desktopEvaluation = Runnable {
        if (foreground && !touchpad) {
            launcher.refreshLiveState()
            maybeAutoLaunchLauncher()
            refreshUi()
        }
    }
    private val externalWindowsChanged = Runnable { scheduleDesktopEvaluation() }

    private fun scheduleDesktopEvaluation() {
        lifecycleHandler.removeCallbacks(desktopEvaluation)
        if (foreground && !touchpad) lifecycleHandler.postDelayed(desktopEvaluation, 700L)
    }
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private var pendingTouchpadHealthCheck: Runnable? = null
    private var touchpadTransitioning = false
    private var touchpadExitReason = "none"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoverStaleTouchpadWindow()
        settingsStore = LauncherSettingsStore(this)
        displays = DisplaySession(this, session)
        launcher = ExternalTaskLauncher(this, session)
        externalOverlayMenu = ExternalOverlayMenu(this, launcher, settingsStore)
        launcher.setListener { refreshUi(); scheduleDesktopEvaluation() }
        input = InputBridge(this, RootBridge(), session, launcher)
        displays.setListener {
            if (displays.lastChangeWasDisconnect) {
                autoLauncherAttempted = false
                externalOverlayMenu?.onDisplayDisconnected()
                launcher.onDisplayDisconnected()
                input.stop()
                touchpadView?.cancel()
                if (touchpad) {
                    scheduleTouchpadHealthCheck(getString(R.string.display_interrupted), 900L)
                }
            }
            if (displays.lastChangeWasReconnect) {
                cancelTouchpadHealthCheck()
                launcher.onDisplayReconnected(session.displayId, session.uniqueId)
                autoLauncherAttempted = false
                scheduleDesktopEvaluation()
            }
            if (touchpad && (session.displayId != touchpadDisplay ||
                        session.displayStatus != SessionState.DisplayStatus.CONNECTED)) {
                scheduleTouchpadHealthCheck(getString(R.string.display_interrupted), 900L)
            } else if (touchpad) {
                // A display notification must not cancel a pending service-loss check.
                if (!TextRelayAccessibilityService.isEnabled()) {
                    scheduleTouchpadHealthCheck(getString(R.string.accessibility_unavailable), 900L)
                } else cancelTouchpadHealthCheck()
            }
            refreshUi()
        }
        displays.discoverExternalDisplay()
        setContent { XRTheme { DesktopContent() } }
        // Auto-launch only when enabled and no confirmed external app is active.
        scheduleDesktopEvaluation()
    }

    @Composable
    private fun DesktopContent() {
        // Keep one Compose root attached for both pages, including Back handling
        // and AndroidView cleanup, instead of replacing the Activity content view.
        BackHandler(enabled = touchpad) { leaveTouchpad("user_back") }
        if (touchpad) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { touchpadView ?: createTouchpadView().also { touchpadView = it } },
                    modifier = Modifier.fillMaxSize().semantics {
                        customActions = listOf(CustomAccessibilityAction(getString(R.string.exit_touchpad)) {
                            leaveTouchpad("accessibility_exit"); true
                        })
                    },
                    onRelease = { view ->
                        view.cancel()
                        if (touchpadView === view) touchpadView = null
                    }
                )

            }
        } else {
            MainScreen()
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        TextRelayAccessibilityService.addConnectionListener(accessibilityConnection)
        TextRelayAccessibilityService.addWindowListener(externalWindowsChanged)
    }

    override fun onResume() {
        super.onResume()
        displays.discoverExternalDisplay()
        session.accessibilityEnabled = TextRelayAccessibilityService.isEnabled()
        if (touchpad) {
            if (!session.accessibilityEnabled || session.displayStatus != SessionState.DisplayStatus.CONNECTED) {
                scheduleTouchpadHealthCheck(getString(R.string.controls_unavailable), 900L)
            } else if (!input.start()) {
                scheduleTouchpadHealthCheck(getString(R.string.input_recovering), 700L)
            } else {
                cancelTouchpadHealthCheck()
                applyTouchpadWindow()
            }
        } else {
            scheduleDesktopEvaluation()
        }
        refreshUi()
    }

    private fun maybeAutoLaunchLauncher() {
        if (autoLauncherAttempted || touchpad) return
        if (!::launcher.isInitialized || !::displays.isInitialized) return
        if (!settingsStore.read().autoLaunchExternalLauncher) return
        if (!foreground || session.displayId <= 0 || session.displayStatus != SessionState.DisplayStatus.CONNECTED) return
        if (!launcher.shouldAutoOpenLauncher()) return
        autoLauncherAttempted = true
        val launched = launcher.launchLauncher()
        if (!launched) {
            // Keep the phone control page available when ColorOS rejects the
            // external task. The user can retry manually after the display is
            // ready.
            // One automatic attempt per display connection; manual retry remains available.
            refreshUi()
        }
    }

    override fun onStop() {
        // Do not tear down the touchpad when the phone Activity temporarily loses
        // focus (for example when the user presses the system Home key). Android
        // will stop delivering touch events while we are backgrounded, but the
        // session must remain intact so it can resume when XR Desktop returns.
        foreground = false
        lifecycleHandler.removeCallbacks(desktopEvaluation)
        TextRelayAccessibilityService.removeWindowListener(externalWindowsChanged)
        TextRelayAccessibilityService.removeConnectionListener(accessibilityConnection)
        super.onStop()
    }

    override fun onDestroy() {
        cancelTouchpadHealthCheck()
        externalOverlayMenu?.hide()
        leaveTouchpad("activity_destroyed", rebuildUi = false)
        input.close()
        launcher.release()
        displays.release()
        lifecycleHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshUi() { runOnUiThread { refresh?.invoke() } }

    private fun enterTouchpad() {
        if (touchpadTransitioning || touchpad) return
        touchpadTransitioning = true
        try {
            displays.discoverExternalDisplay()
            if (session.displayId <= 0) return toast(getString(R.string.connect_first))
            if (!TextRelayAccessibilityService.isEnabled()) return toast(getString(R.string.enable_accessibility))
            if (!input.start()) {
                Log.w("XRDesktop", "touchpad start failed: ${session.lastInputResult}")
                return toast(session.lastInputResult.ifEmpty { getString(R.string.touchpad_failed) })
            }
            originalBrightness = window.attributes.screenBrightness
            originalUi = window.decorView.systemUiVisibility
            getSharedPreferences(TOUCHPAD_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putFloat(KEY_BRIGHTNESS, originalBrightness)
                .putInt(KEY_UI, originalUi)
                .apply()
            touchpadDisplay = session.displayId
            session.touchpadMode = true
            session.desktopStatus = SessionState.DesktopStatus.TOUCHPAD_ACTIVE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyTouchpadWindow()
            touchpadView = createTouchpadView()
            touchpad = true
            touchpadExitReason = "none"
            Log.i("XRDesktop", "touchpad entered display=${session.displayId}")
        } catch (error: RuntimeException) {
            Log.e("XRDesktop", "touchpad enter failed", error)
            input.stop()
            // Restore saved brightness/flags on partial entry failures too.
            touchpadTransitioning = false
            leaveTouchpad("enter_failed")
            toast(getString(R.string.operation_failed, error.message ?: error.javaClass.simpleName))
        } finally {
            touchpadTransitioning = false
        }
    }

    private fun recoverStaleTouchpadWindow() {
        val prefs = getSharedPreferences(TOUCHPAD_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        val params = window.attributes
        params.screenBrightness = prefs.getFloat(KEY_BRIGHTNESS,
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        window.attributes = params
        window.decorView.systemUiVisibility = prefs.getInt(KEY_UI, 0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs.edit().clear().apply()
        Log.w("XRDesktop", "recovered stale touchpad window state")
    }

    private fun applyTouchpadWindow() {
        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun leaveTouchpad(reason: String = "user", rebuildUi: Boolean = true) {
        if (touchpadTransitioning) return
        val prefs = getSharedPreferences(TOUCHPAD_PREFS, MODE_PRIVATE)
        val hadState = touchpad || touchpadView != null || prefs.getBoolean(KEY_ACTIVE, false)
        if (!hadState) return
        touchpadTransitioning = true
        touchpadExitReason = reason
        cancelTouchpadHealthCheck()
        touchpad = false
        touchpadDisplay = -1
        session.touchpadMode = false
        session.desktopStatus = when {
            session.displayStatus != SessionState.DisplayStatus.CONNECTED -> SessionState.DesktopStatus.WAITING_FOR_RECONNECT
            session.externalPresence == ExternalRuntimeState.Presence.APPLICATION -> SessionState.DesktopStatus.RUNNING_EXTERNAL_APP
            else -> SessionState.DesktopStatus.DISPLAY_READY
        }
        externalOverlayMenu?.hide()
        val view = touchpadView
        touchpadView = null
        try {
            view?.cancel()
        } catch (error: RuntimeException) {
            Log.w("XRDesktop", "touchpad view cancel failed", error)
        }
        input.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val params = window.attributes
            params.screenBrightness = if (prefs.getBoolean(KEY_ACTIVE, false)) {
                prefs.getFloat(KEY_BRIGHTNESS, originalBrightness)
            } else originalBrightness
            window.attributes = params
            window.decorView.systemUiVisibility = if (prefs.getBoolean(KEY_ACTIVE, false)) {
                prefs.getInt(KEY_UI, originalUi)
            } else originalUi
        } catch (error: RuntimeException) {
            Log.w("XRDesktop", "restore touchpad window failed", error)
        }
        prefs.edit().clear().apply()
        if (rebuildUi && !isFinishing && !isDestroyed) {
            launcher.refreshLiveState()
            refreshUi()
            scheduleDesktopEvaluation()
        }
        touchpadTransitioning = false
        Log.i("XRDesktop", "touchpad left reason=$reason")
    }

    private fun scheduleTouchpadHealthCheck(reason: String, delayMs: Long) {
        if (!touchpad || isFinishing || isDestroyed) return
        pendingTouchpadHealthCheck?.let(lifecycleHandler::removeCallbacks)
        val check = Runnable {
            pendingTouchpadHealthCheck = null
            if (!touchpad || isFinishing || isDestroyed) return@Runnable
            displays.discoverExternalDisplay()
            session.accessibilityEnabled = TextRelayAccessibilityService.isEnabled()
            val displayReady = session.displayStatus == SessionState.DisplayStatus.CONNECTED &&
                    session.displayId > 0 && session.displayId == touchpadDisplay
            if (!session.accessibilityEnabled || !displayReady || !input.start()) {
                Log.w("XRDesktop", "touchpad health check failed reason=$reason, state=${session.lastInputResult}")
                leaveTouchpad(reason)
                toast(getString(if (!displayReady) R.string.paused_display else R.string.paused_service))
            } else {
                applyTouchpadWindow()
                refreshUi()
            }
        }
        pendingTouchpadHealthCheck = check
        lifecycleHandler.postDelayed(check, delayMs)
    }

    private fun cancelTouchpadHealthCheck() {
        pendingTouchpadHealthCheck?.let(lifecycleHandler::removeCallbacks)
        pendingTouchpadHealthCheck = null
    }

    private fun createTouchpadView(): TouchpadView {
        val current = settingsStore.read()
        return TouchpadView(this, input, session, ::showTextDialog,
            GestureSettings(current.pointerSensitivity, current.scrollSensitivity,
                current.fourFingerScreenshotEnabled),
            object : ExternalScreenshotter {
                override fun capture(displayId: Int, callback: ExternalScreenshotter.Callback) {
                    captureExternalScreenshot { result -> callback.onResult(result) }
                }
            }, object : TouchpadRegionListener {
                override fun onMultitaskClick() {
                    showExternalOverlayMenu("recent")
                }
                override fun onExitClick() { leaveTouchpad("exit_region") }
                override fun onExternalBackClick() {
                    val view = touchpadView
                    view?.showFeedback(getString(R.string.root_back_sending))
                    if (!input.back(session.displayId) { _, message ->
                            if (touchpad && touchpadView === view) view?.showFeedback(message)
                        }) view?.showFeedback(getString(R.string.external_back_unavailable))
                }
            })
    }

    private fun showExternalOverlayMenu(mode: String) {
        if (!touchpad) return
        displays.discoverExternalDisplay()
        val displayId = session.displayId
        if (displayId <= 0 || session.displayStatus != SessionState.DisplayStatus.CONNECTED) {
            touchpadView?.showFeedback(getString(R.string.display_disconnected))
            return
        }
        val overlay = externalOverlayMenu
        if (overlay != null && overlay.show(displayId, mode, object : ExternalOverlayMenu.Listener {
                override fun onMessage(message: String) {
                    runOnUiThread {
                        touchpadView?.showFeedback(message)

                    }
                }
            })) {
            touchpadView?.showFeedback(getString(R.string.menu_opened))
            return
        }
        // ColorOS may reject TYPE_APPLICATION_OVERLAY on the external display.
        // Keep the existing Activity fallback so the feature remains usable.
        if (launcher.launchExternalQuickMenu(mode)) {
            touchpadView?.showFeedback(getString(R.string.menu_opened))
        } else {
            touchpadView?.showFeedback(getString(R.string.menu_failed))
        }
    }

    private fun captureExternalScreenshot(callback: (ScreenshotResult) -> Unit) {
        TextRelayAccessibilityService.captureDisplay(session.displayId, this) { result ->
            if (isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@captureDisplay
            runOnUiThread {
                if (isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@runOnUiThread
                callback(result)
                when (result) {
                    is ScreenshotResult.Saved -> toast(getString(R.string.screenshot_saved))
                    is ScreenshotResult.Failed -> toast(getString(R.string.screenshot_failed, result.reason))
                }
            }
        }
    }

    private fun showTextDialog() {
        val field = EditText(this)
        field.setText(getSharedPreferences("xr_session", MODE_PRIVATE).getString("text_draft", ""))
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.text_title))
            .setView(field).setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.send), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = field.text.toString()
                getSharedPreferences("xr_session", MODE_PRIVATE).edit().putString("text_draft", text).apply()
                if (input.sendText(session.displayId, text)) {
                    dialog.dismiss()
                } else toast(getString(R.string.text_failed))
            }
        }
        dialog.setOnDismissListener {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(field.windowToken, 0)
            if (touchpad) applyTouchpadWindow()
        }
        dialog.show()
        field.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        var tick by remember { mutableIntStateOf(0) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showAbout by rememberSaveable { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }
        var manageFavorites by remember { mutableStateOf(false) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var confirmation by remember { mutableIntStateOf(0) }
        var currentSettings by remember { mutableStateOf(settingsStore.read()) }
        refresh = { tick++ }
        LaunchedEffect(tick) { currentSettings = settingsStore.read() }
        // Read observable tick in composition: asynchronous display/service/launch callbacks refresh this page.
        @Suppress("UNUSED_VARIABLE") val revision = tick
        val connected = session.displayStatus == SessionState.DisplayStatus.CONNECTED && session.displayId > 0
        val accessibilityReady = TextRelayAccessibilityService.isEnabled()
        val overlayReady = Settings.canDrawOverlays(this@ComposeMainActivity)
        val activeApp = launcher.getActiveApp()
        val homeScroll = rememberScrollState()
        val settingsScroll = rememberScrollState()
        val aboutScroll = rememberScrollState()
        BackHandler(enabled = showAbout || tab != 0) { if (showAbout) showAbout = false else tab = 0 }
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(if (showAbout) stringResource(R.string.about) else "XR Desktop") },
                    navigationIcon = {
                        if (showAbout) IconButton(onClick = { showAbout = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    })
            },
            bottomBar = {
                if (!showAbout) NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Home, null) }, label = { Text(stringResource(R.string.home)) })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.settings)) })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(when {
                showAbout -> aboutScroll; tab == 0 -> homeScroll; else -> settingsScroll
            })
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    showAbout -> AboutContent()
                    tab == 0 -> {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(if (connected) R.string.connected else R.string.waiting_display),
                                    style = MaterialTheme.typography.titleLarge)
                                Text(if (connected) "${session.displayWidth} × ${session.displayHeight}" else
                                    stringResource(if (session.desktopStatus == SessionState.DesktopStatus.WAITING_FOR_RECONNECT)
                                        R.string.reconnect_hint else R.string.connect_hint),
                                    style = MaterialTheme.typography.bodyMedium)
                                if (connected) Text(
                                    when (session.externalPresence) {
                                        ExternalRuntimeState.Presence.APPLICATION -> stringResource(R.string.live_external_app, session.runningExternalLabel)
                                        ExternalRuntimeState.Presence.EMPTY -> stringResource(R.string.external_empty)
                                        ExternalRuntimeState.Presence.DESKTOP -> stringResource(R.string.external_desktop_visible)
                                        else -> stringResource(R.string.external_live_unknown)
                                    }, style = MaterialTheme.typography.bodyMedium)
                                activeApp?.let { Text(stringResource(R.string.active_app, it.label), style = MaterialTheme.typography.bodyMedium) }
                                if (!connected && session.lastDisconnectAt.isNotBlank()) {
                                    Text(stringResource(R.string.disconnected_at, session.lastDisconnectAt), style = MaterialTheme.typography.bodySmall)
                                }
                                if (!accessibilityReady) TextButton(onClick = { tab = 1 }) {
                                    Text(stringResource(R.string.permission_hint))
                                }
                            }
                        }
                        Button(onClick = { enterTouchpad() }, enabled = connected && accessibilityReady,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 136.dp), shape = RoundedCornerShape(28.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.TouchApp, null, Modifier.size(32.dp))
                                Text(stringResource(R.string.enter_touchpad), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                Text(stringResource(R.string.touchpad_hint), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LargeAction(stringResource(R.string.continue_app), Icons.Default.PlayArrow,
                                Modifier.weight(1f), connected && activeApp != null, 96) { launcher.resumeActiveApp() }
                            LargeAction(stringResource(R.string.open_launcher), Icons.Default.Apps,
                                Modifier.weight(1f), connected, 96) { displays.discoverExternalDisplay(); launcher.launchLauncher() }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LargeAction(stringResource(R.string.input_text), Icons.Default.Keyboard,
                                Modifier.weight(1f), connected && accessibilityReady, 80) { showTextDialog() }
                            LargeAction(stringResource(R.string.screenshot), Icons.Default.Screenshot,
                                Modifier.weight(1f), connected && accessibilityReady, 80) { captureExternalScreenshot { } }
                        }
                        OutlinedButton(onClick = { manageFavorites = false; showApps = true }, enabled = connected,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(20.dp)) {
                            Icon(Icons.Default.Apps, null); Spacer(Modifier.size(10.dp)); Text(stringResource(R.string.choose_app))
                        }
                    }
                    else -> {
                        SettingsSection(stringResource(R.string.permission_section)) {
                            PermissionRow(stringResource(R.string.accessibility_title), stringResource(R.string.accessibility_hint),
                                accessibilityReady, stringResource(R.string.open_settings)) {
                                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                catch (error: RuntimeException) { toast(getString(R.string.operation_failed, error.message)) }
                            }
                            HorizontalDivider()
                            PermissionRow(stringResource(R.string.overlay_title), stringResource(R.string.overlay_hint),
                                overlayReady, stringResource(R.string.grant_permission)) {
                                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                                catch (error: RuntimeException) { toast(getString(R.string.operation_failed, error.message)) }
                            }
                        }
                        SettingsSection(stringResource(R.string.interaction_section)) {
                            Text(stringResource(R.string.pointer_sensitivity) + " · " + "%.1f".format(currentSettings.pointerSensitivity))
                            Slider(value = currentSettings.pointerSensitivity, onValueChange = {
                                currentSettings = currentSettings.copy(pointerSensitivity = it); settingsStore.write(currentSettings)
                            }, valueRange = 0.6f..3f)
                            Text(stringResource(R.string.scroll_sensitivity) + " · " + "%.1f".format(currentSettings.scrollSensitivity))
                            Slider(value = currentSettings.scrollSensitivity, onValueChange = {
                                currentSettings = currentSettings.copy(scrollSensitivity = it); settingsStore.write(currentSettings)
                            }, valueRange = 0.8f..4f)
                            SettingSwitch(stringResource(R.string.four_finger), currentSettings.fourFingerScreenshotEnabled) {
                                currentSettings = currentSettings.copy(fourFingerScreenshotEnabled = it); settingsStore.write(currentSettings)
                            }
                        }
                        SettingsSection(stringResource(R.string.launcher_section)) {
                            SettingSwitch(stringResource(R.string.auto_launcher), currentSettings.autoLaunchExternalLauncher,
                                stringResource(R.string.auto_launcher_hint)) {
                                currentSettings = currentSettings.copy(autoLaunchExternalLauncher = it); settingsStore.write(currentSettings)
                            }
                            SettingSwitch(stringResource(R.string.restore_app), currentSettings.restoreLastAppAfterReconnect,
                                stringResource(R.string.restore_hint)) {
                                currentSettings = currentSettings.copy(restoreLastAppAfterReconnect = it); settingsStore.write(currentSettings)
                            }
                            OutlinedButton(onClick = { manageFavorites = true; showApps = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(stringResource(R.string.favorites))
                            }
                            Text(stringResource(R.string.grid_density), style = MaterialTheme.typography.titleSmall)
                            GridMode.entries.forEach { mode ->
                                ChoiceRow(gridModeLabel(mode), mode == currentSettings.gridMode) {
                                    currentSettings = currentSettings.copy(gridMode = mode); settingsStore.write(currentSettings)
                                }
                            }
                            Text(stringResource(R.string.sort_mode), style = MaterialTheme.typography.titleSmall)
                            SortMode.entries.forEach { mode ->
                                ChoiceRow(sortModeLabel(mode), mode == currentSettings.sortMode) {
                                    currentSettings = currentSettings.copy(sortMode = mode); settingsStore.write(currentSettings)
                                }
                            }
                        }
                        SettingsSection(stringResource(R.string.language)) {
                            val selected = AppLanguage.selected(this@ComposeMainActivity)
                            listOf("" to R.string.language_system, "zh-CN" to R.string.language_zh, "en" to R.string.language_en).forEach { (code, label) ->
                                ChoiceRow(stringResource(label), selected == code) {
                                    if (selected != code) AppLanguage.apply(this@ComposeMainActivity, code)
                                }
                            }
                        }
                        SettingsSection(stringResource(R.string.gesture_help)) {
                            Text(stringResource(R.string.gesture_body), style = MaterialTheme.typography.bodyMedium)
                        }
                        SettingsSection(stringResource(R.string.maintenance)) {
                            SettingsLink(stringResource(R.string.clear_recents)) { confirmation = 1 }
                            SettingsLink(stringResource(R.string.reset_settings)) { confirmation = 2 }
                            SettingsLink(stringResource(R.string.diagnostics)) { showDiagnostics = true }
                            SettingsLink(stringResource(R.string.about)) { showAbout = true }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (showApps) {
            AppPickerDialog(currentSettings, manageFavorites, onToggleFavorite = {
                settingsStore.setFavorite(it, !currentSettings.favoriteComponents.contains(it)); currentSettings = settingsStore.read()
            }, onLaunch = { app ->
                if (launcher.launchPackage(app.packageName, app.className)) { showApps = false; tick++ }
                else toast(getString(R.string.launch_failed_app, app.label) + "\n" + session.lastError)
            }, onDismiss = { showApps = false })
        }
        if (confirmation != 0) ComposeAlertDialog(onDismissRequest = { confirmation = 0 },
            title = { Text(stringResource(if (confirmation == 1) R.string.clear_recents else R.string.reset_settings)) },
            text = { Text(stringResource(if (confirmation == 1) R.string.clear_confirm else R.string.reset_confirm)) },
            dismissButton = { TextButton(onClick = { confirmation = 0 }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                if (confirmation == 1) settingsStore.clearRecent() else settingsStore.reset()
                toast(getString(if (confirmation == 1) R.string.recents_cleared else R.string.reset_done))
                currentSettings = settingsStore.read(); confirmation = 0; tick++
            }) { Text(stringResource(R.string.confirm)) } })
        if (showDiagnostics) ComposeAlertDialog(onDismissRequest = { showDiagnostics = false },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.diagnostics)) }, text = {
                SelectionContainer { Text(statusText(tick) + "\n\n" + TextRelayAccessibilityService.diagnostics() +
                    "\n\n" + launcher.queryTaskPlacement(), Modifier.verticalScroll(rememberScrollState())) }
            })
    }

    private fun statusText(@Suppress("UNUSED_PARAMETER") tick: Int): String =
        "Display: ${session.displayStatus} id=${session.displayId} ${session.displayWidth}x${session.displayHeight}" +
            "\nAccessibility: ${TextRelayAccessibilityService.isEnabled()}" +
            "\nInput: ${session.inputBackend}\n${session.lastInputResult}" +
            "\nTouchpad exit: $touchpadExitReason"
}

@Composable
private fun LargeAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier, enabled: Boolean, minimumHeight: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = minimumHeight.dp), shape = RoundedCornerShape(22.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun PermissionRow(title: String, description: String, ready: Boolean, action: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (ready) Text(stringResource(R.string.ready), color = MaterialTheme.colorScheme.primary)
        }
        Text(description, style = MaterialTheme.typography.bodySmall)
        if (!ready) OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(action) }
    }
}

@Composable
private fun SettingSwitch(label: String, enabled: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    // One accessible click target for the whole row, not a tiny checkbox.
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (selected) "✓ $label" else label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingsLink(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(label) }
}

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    var showLicense by remember { mutableStateOf(false) }
    SettingsSection("XR Desktop") {
        Icon(Icons.Default.Info, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.version, version))
        Text(stringResource(R.string.copyright))
        Text(stringResource(R.string.copyright_note), style = MaterialTheme.typography.bodySmall)
    }
    SettingsSection(stringResource(R.string.privacy_title)) { Text(stringResource(R.string.privacy_body)) }
    SettingsSection(stringResource(R.string.open_source)) {
        Text(stringResource(R.string.dependencies))
        SettingsLink(stringResource(R.string.apache_license)) { showLicense = true }
    }
    if (showLicense) {
        val license = remember { context.assets.open("apache-2.0.txt").bufferedReader().use { it.readText() } }
        ComposeAlertDialog(onDismissRequest = { showLicense = false }, title = { Text(stringResource(R.string.apache_license)) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.close)) } },
            text = { SelectionContainer { Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } })
    }
}

@Composable
private fun AppPickerDialog(settings: LauncherSettings, manageFavorites: Boolean, onToggleFavorite: (String) -> Unit,
    onLaunch: (AppCatalog.Entry) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { AppCatalog().load(context) }
    ComposeAlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        title = { Text(stringResource(if (manageFavorites) R.string.favorites else R.string.choose_app)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { it.componentKey() }) { app ->
                    if (manageFavorites) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(app.label.toString(), Modifier.weight(1f))
                            Checkbox(settings.favoriteComponents.contains(app.componentKey()), onCheckedChange = { onToggleFavorite(app.componentKey()) })
                        }
                    } else OutlinedButton(onClick = { onLaunch(app) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(app.label.toString(), textAlign = TextAlign.Center)
                    }
                }
            }
        })
}

@Composable
private fun gridModeLabel(mode: GridMode): String = stringResource(when (mode) {
    GridMode.COMPACT -> R.string.compact
    GridMode.STANDARD -> R.string.standard
    GridMode.LARGE -> R.string.large
})

@Composable
private fun sortModeLabel(mode: SortMode): String = stringResource(when (mode) {
    SortMode.FAVORITES_FIRST -> R.string.favorites_first
    SortMode.RECENT -> R.string.recent_first
    SortMode.ALPHABETICAL -> R.string.alphabetical
})
