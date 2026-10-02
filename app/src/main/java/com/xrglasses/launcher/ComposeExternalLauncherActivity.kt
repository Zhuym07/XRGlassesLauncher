package com.xrglasses.launcher

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComposeExternalLauncherActivity : LocalizedComponentActivity() {
    private val session = SessionState()
    private lateinit var displays: DisplaySession
    private lateinit var launcher: ExternalTaskLauncher
    private lateinit var settingsStore: LauncherSettingsStore
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        settingsStore = LauncherSettingsStore(this)
        displays = DisplaySession(this, session)
        displays.discoverExternalDisplay()
        val display: Display? = windowManager.defaultDisplay
        if (display == null || display.displayId <= 0) {
            getSharedPreferences("xr_session", MODE_PRIVATE).edit().putString(
                "launch_status", getString(R.string.launcher_redirected)).apply()
            finish()
            return
        }
        session.displayId = display.displayId
        val ownDisplay = session.displayId
        displays.setListener { if (session.displayId != ownDisplay) finish() }
        launcher = ExternalTaskLauncher(this, session)
        setContent { XRTheme { LauncherScreen() } }
    }

    override fun onResume() {
        super.onResume()
        if (::displays.isInitialized) displays.discoverExternalDisplay()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::launcher.isInitialized) launcher.release()
        if (::displays.isInitialized) displays.release()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun LauncherScreen() {
        var query by remember { mutableStateOf("") }
        var tick by remember { mutableIntStateOf(0) }
        var settings by remember { mutableStateOf(settingsStore.read()) }
        val focusRecents = intent.getBooleanExtra("focus_recents", false)
        var lastAction by remember { mutableStateOf(if (focusRecents) getString(R.string.recents) else "") }
        var launchingKey by remember { mutableStateOf<String?>(null) }
        val activeApp = launcher.getActiveApp()
        val time = remember { mutableStateOf(currentTime()) }
        val apps = remember(tick, settings) { AppCatalog().load(this) }
        val filtered = apps.filter {
            query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        val favorites = filtered.filter { settings.favoriteComponents.contains(it.componentKey()) }
        val recent = filtered.filter { it.lastUsed > 0L }.sortedWith { a, b ->
            AppCatalog.compare(a.lastUsed, a.label.toString(), b.lastUsed, b.label.toString())
        }
        val all = filtered.sortedWith { a, b ->
            when (settings.sortMode) {
                SortMode.FAVORITES_FIRST -> {
                    val af = settings.favoriteComponents.contains(a.componentKey())
                    val bf = settings.favoriteComponents.contains(b.componentKey())
                    if (af != bf) if (af) -1 else 1
                    else a.label.toString().compareTo(b.label.toString(), ignoreCase = true)
                }
                SortMode.RECENT -> AppCatalog.compare(a.lastUsed, a.label.toString(), b.lastUsed, b.label.toString())
                SortMode.ALPHABETICAL -> a.label.toString().compareTo(b.label.toString(), ignoreCase = true)
            }
        }
        val columns = when (settings.gridMode) {
            GridMode.COMPACT -> GridCells.Fixed(6)
            GridMode.STANDARD -> GridCells.Fixed(5)
            GridMode.LARGE -> GridCells.Fixed(4)
        }
        val refresh = {
            tick++
            settings = settingsStore.read()
            launchingKey = null
        }
        launcher.setListener { runOnUiThread { refresh() } }
        LaunchedEffect(Unit) {
            while (true) {
                time.value = currentTime()
                kotlinx.coroutines.delay(30_000)
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("XR Desktop", fontWeight = FontWeight.Bold)
                            Text("${time.value}  ·  ${session.displayWidth}×${session.displayHeight}", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = {
                        AssistChip(
                            onClick = { },
                            label = { Text("display ${session.displayId}") },
                            leadingIcon = { Icon(Icons.Default.Wifi, null) }
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { tick++ }) { Icon(Icons.Default.Settings, getString(R.string.refresh)) }
                    }
                )
            }
        ) { padding ->
            Row(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(Modifier.width(240.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 4.dp) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(getString(R.string.desktop_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(getString(R.string.desktop_hint), style = MaterialTheme.typography.bodyMedium)
                            StatusPill(getString(R.string.independent_display), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                            StatusPill(getString(R.string.local_control), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, getString(R.string.clear)) }
                        },
                        placeholder = { Text(getString(R.string.search_apps)) }
                    )
                    Text(resources.getQuantityString(R.plurals.apps_count, filtered.size, filtered.size), style = MaterialTheme.typography.labelLarge)
                    if (lastAction.isNotBlank()) {
                        Text(lastAction, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(getString(R.string.favorite_tip), style = MaterialTheme.typography.bodySmall)
                    activeApp?.let { active ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(getString(R.string.current_external), style = MaterialTheme.typography.labelLarge)
                                Text(active.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(getString(R.string.last_confirmed, SimpleDateFormat("HH:mm", resources.configuration.locales[0]).format(Date(active.lastConfirmedAt))), style = MaterialTheme.typography.bodySmall)
                                Button(onClick = {
                                    launchingKey = active.componentKey()
                                    lastAction = getString(R.string.resuming_app, active.label)
                                    if (!launcher.resumeActiveApp()) {
                                        launchingKey = null
                                        lastAction = session.lastError
                                        tick++
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(getString(R.string.continue_app))
                                }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (launchingKey != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    SectionHeader(getString(R.string.favorites), Icons.Default.Favorite, favorites.size)
                    if (favorites.isEmpty()) {
                        EmptyHint(getString(R.string.empty_favorites))
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            rowItems(favorites, key = { "fav-" + it.componentKey() }) { app ->
                                AppCard(app, true, launchingKey == app.componentKey(), settings, onLaunch = {
                                    launchingKey = app.componentKey()
                                    lastAction = getString(R.string.opening_app, app.label)
                                    val ok = launcher.launchPackage(app.packageName, app.className)
                                    if (!ok) { launchingKey = null; lastAction = session.lastError }
                                }, onToggleFavorite = {
                                    settingsStore.setFavorite(app.componentKey(), false)
                                    settings = settingsStore.read()
                                    tick++
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (recent.isNotEmpty()) {
                        SectionHeader(getString(R.string.recents), Icons.Default.Speed, recent.size)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            rowItems(recent.take(8), key = { "recent-" + it.componentKey() }) { app ->
                                AppCard(app, settings.favoriteComponents.contains(app.componentKey()), launchingKey == app.componentKey(), settings, onLaunch = {
                                    launchingKey = app.componentKey()
                                    lastAction = getString(R.string.opening_app, app.label)
                                    val ok = launcher.launchPackage(app.packageName, app.className)
                                    if (!ok) { launchingKey = null; lastAction = session.lastError }
                                }, onToggleFavorite = {
                                    settingsStore.setFavorite(app.componentKey(), !settings.favoriteComponents.contains(app.componentKey()))
                                    settings = settingsStore.read()
                                    tick++
                                })
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    SectionHeader(getString(R.string.all_apps), Icons.Default.GridView, all.size)
                    LazyVerticalGrid(
                        columns = columns,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItems(all, key = { it.componentKey() }) { app ->
                            AppCard(app, settings.favoriteComponents.contains(app.componentKey()), launchingKey == app.componentKey(), settings, onLaunch = {
                                launchingKey = app.componentKey()
                                lastAction = getString(R.string.opening_app, app.label)
                                val ok = launcher.launchPackage(app.packageName, app.className)
                                if (!ok) { launchingKey = null; lastAction = session.lastError }
                            }, onToggleFavorite = {
                                val enabled = !settings.favoriteComponents.contains(app.componentKey())
                                settingsStore.setFavorite(app.componentKey(), enabled)
                                settings = settingsStore.read()
                                tick++
                            })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusPill(text: String, background: ComposeColor, foreground: ComposeColor) {
        Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(100.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun EmptyHint(text: String) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
            Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AppCard(
        app: AppCatalog.Entry,
        favorite: Boolean,
        launching: Boolean,
        settings: LauncherSettings,
        onLaunch: () -> Unit,
        onToggleFavorite: () -> Unit,
    ) {
        val iconSize = when (settings.gridMode) {
            GridMode.COMPACT -> 46.dp
            GridMode.STANDARD -> 58.dp
            GridMode.LARGE -> 72.dp
        }
        val iconPx = with(LocalDensity.current) { iconSize.roundToPx() }
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onLaunch, onLongClick = onToggleFavorite),
            colors = CardDefaults.cardColors(containerColor = if (favorite) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(Modifier.fillMaxWidth().padding(10.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                                layoutParams = ViewGroup.LayoutParams(iconPx, iconPx)
                            }
                        },
                        update = { image ->
                            image.layoutParams = ViewGroup.LayoutParams(iconPx, iconPx)
                            image.setImageDrawable(app.icon)
                        },
                        modifier = Modifier.size(iconSize).clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(app.label.toString(), maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    if (launching) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (favorite) getString(R.string.favorited) else getString(R.string.not_favorited),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                )
            }
        }
    }

    private fun currentTime(): String = SimpleDateFormat("HH:mm", resources.configuration.locales[0]).format(Date())
}
