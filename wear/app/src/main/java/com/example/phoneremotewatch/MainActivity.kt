package com.example.phoneremotewatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs

private val attributeNames = listOf(
    "Exposure", "Brightness", "Contrast", "Saturation", "Vibrance", "Highlights",
    "Shadows", "Whites", "Blacks", "Gamma", "Brilliance"
)

private val hapticNames = listOf("Battery saver", "Medium", "High")

private enum class Glyph { PREV, NEXT, PLAY, PAUSE }

private fun norm(value: Float, total: Int): Int {
    if (total <= 0) return 0
    return ((value / total.toFloat()) * 999f).toInt().coerceIn(0, 999)
}

class MainActivity : ComponentActivity() {

    private val link: BleLink by lazy { LinkHolder.get(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val editValues = mutableStateListOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private var sendPending = false
    private var pendingIndex = 0
    private var permissionVersion by mutableIntStateOf(0)
    private var hintText by mutableStateOf("")
    private var musicOpen by mutableStateOf(false)
    private var contactsOpen by mutableStateOf(false)
    private var hiddenCallTick by mutableIntStateOf(-1)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionVersion += 1
            if (hasBlePermissions()) {
                startLink(false)
            } else {
                link.status = "Bluetooth permission was denied"
            }
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionVersion += 1
            if (granted) {
                link.changeHapticsOn(true)
            } else {
                hintText = "Microphone permission was denied"
            }
        }

    private fun blePermissionList(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyArray()
        }
    }

    private fun hasBlePermissions(): Boolean {
        return blePermissionList().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMic(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun toggleHaptics() {
        if (link.hapticsOn) {
            link.changeHapticsOn(false)
        } else if (hasMic()) {
            link.changeHapticsOn(true)
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startLink(restart: Boolean) {
        if (hasBlePermissions()) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, LinkService::class.java)
                        .setAction(if (restart) LinkService.ACTION_RESTART else LinkService.ACTION_START)
                )
            } catch (e: Exception) {
                link.status = "Cannot start link: ${e.message}"
            }
        } else {
            permissionLauncher.launch(blePermissionList())
        }
    }

    private fun stopLink() {
        try {
            startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
        } catch (e: Exception) {
            link.stop()
        }
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(LinkService.EXTRA_OPEN_MUSIC, false) == true) {
            musicOpen = true
            contactsOpen = false
        }
    }

    private fun openGalleryOnPhone() {
        hintText = "Opening gallery on the iPhone..."
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                if (link.mode != WatchMode.IDLE) return
                if (attempt >= 4) {
                    hintText = "No answer. Open the Phone Remote app on the iPhone and try again."
                    return
                }
                attempt += 1
                link.sendEvent("APP G")
                handler.postDelayed(this, 2000L)
            }
        }
        handler.post(task)
    }

    private fun requestSend(index: Int) {
        pendingIndex = index
        if (sendPending) return
        sendPending = true
        handler.postDelayed({
            sendPending = false
            link.sendEvent("E $pendingIndex ${editValues[pendingIndex]}")
        }, 40L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleIntent(intent)
        addOnNewIntentListener { handleIntent(it) }
        setContent {
            MaterialTheme {
                AppScreen()
            }
        }
        startLink(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- Navigation ----------

    @Composable
    private fun AppScreen() {
        val showCall = link.callActive && hiddenCallTick != link.callTick

        LaunchedEffect(link.callActive) {
            setShowWhenLocked(link.callActive)
            setTurnScreenOn(link.callActive)
        }

        BackHandler(
            enabled = showCall || link.mode != WatchMode.IDLE || musicOpen || contactsOpen
        ) {
            when {
                showCall -> hiddenCallTick = link.callTick
                link.mode == WatchMode.GALLERY -> link.sendEvent("XG")
                link.mode == WatchMode.VIEWER -> link.sendEvent("BK")
                link.mode == WatchMode.ZOOM -> link.sendEvent("ZD")
                link.mode == WatchMode.EDIT -> link.sendEvent("EX")
                contactsOpen -> contactsOpen = false
                musicOpen -> musicOpen = false
            }
        }

        if (showCall) {
            CallScreen()
        } else {
            when (link.mode) {
                WatchMode.IDLE -> {
                    if (contactsOpen) {
                        ContactsScreen()
                    } else if (musicOpen) {
                        MusicScreen()
                    } else {
                        HubScreen()
                    }
                }
                WatchMode.GALLERY -> PadScreen(zoom = false)
                WatchMode.ZOOM -> PadScreen(zoom = true)
                WatchMode.VIEWER -> ViewerScreen()
                WatchMode.EDIT -> EditScreen()
            }
        }
    }

    // ---------- Status page, menu and settings ----------

    @Composable
    private fun Bubble(
        emoji: String,
        label: String,
        active: Boolean,
        size: Dp = 44.dp,
        onClick: (() -> Unit)? = null
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFF244B2F) else Color(0xFF3C3C40))
                    .border(2.dp, if (active) Color(0xFF4CAF50) else Color(0xFF58585E), CircleShape)
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = (size.value * 0.42f).sp)
            }
            Text(
                text = label,
                fontSize = 8.sp,
                color = if (active) Color(0xFFA5D6A7) else Color(0xFF9E9E9E)
            )
        }
    }

    @Composable
    private fun HubScreen() {
        val pagerState = rememberPagerState(pageCount = { 3 })
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2B2B2E))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> StatusPage()
                    1 -> MenuPage()
                    else -> SettingsPage()
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                for (i in 0..2) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (pagerState.currentPage == i) Color.White else Color(0xFF6A6A70))
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusPage() {
        @Suppress("UNUSED_VARIABLE")
        val version = permissionVersion
        val connected = link.connectedAddress != null
        LaunchedEffect(hintText) {
            if (hintText.isNotEmpty()) {
                delay(3500)
                hintText = ""
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (connected) "iPhone connected" else "Not connected",
                fontSize = 14.sp
            )
            Text(
                text = (if (connected) (if (link.dataReady) "data link ready" else "data link not ready") else link.status) + "  |  watch v7",
                fontSize = 9.sp,
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Bubble("🔵", "Bluetooth", hasBlePermissions(), 36.dp) { startLink(false) }
                Bubble("🔔", "Alerts", hasNotificationPermission(), 36.dp) { requestNotifications() }
                Bubble("🎵", "Music", link.phoneMusic || link.amsReady, 36.dp) {
                    hintText = "Music: " + link.amsStatus
                }
                Bubble("🖼", "Gallery", link.phoneGallery, 36.dp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Bubble("❤", "Health", false, 36.dp)
                Bubble("📞", "Calls", link.ancsReady && link.shareCalls, 36.dp) {
                    hintText = "Calls: " + link.ancsStatus
                }
                Bubble("👤", "Contacts", link.contactCount > 0, 36.dp) {
                    hintText = if (link.contactCount > 0) {
                        "${link.contactCount} contacts synced"
                    } else {
                        "Turn on Contacts sync in the iPhone app"
                    }
                }
                Bubble("🎙", "Mic", hasMic(), 36.dp) {
                    if (!hasMic()) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hintText.isNotEmpty()) hintText else "tap a bubble for details",
                fontSize = 9.sp,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Restart link",
                    fontSize = 10.sp,
                    color = Color(0xFF90CAF9),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { startLink(true) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Text(
                    text = "Stop link",
                    fontSize = 10.sp,
                    color = Color(0xFFEF9A9A),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { stopLink() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }

    @Composable
    private fun MenuPage() {
        val ready = link.dataReady && link.connectedAddress != null
        LaunchedEffect(hintText) {
            if (hintText.isNotEmpty()) {
                delay(2500)
                hintText = ""
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Open", fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Bubble("🖼", "Gallery", ready, 48.dp) {
                    if (ready) {
                        openGalleryOnPhone()
                    } else {
                        hintText = "Connect the iPhone first"
                    }
                }
                Bubble("🎵", "Music", ready, 48.dp) {
                    if (ready) {
                        musicOpen = true
                    } else {
                        hintText = "Connect the iPhone first"
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Bubble("❤", "Health", false, 48.dp) {
                    hintText = "Health is not built yet"
                }
                Bubble("👤", "Contacts", link.contactCount > 0, 48.dp) {
                    contactsOpen = true
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (hintText.isNotEmpty()) hintText else if (ready) "Keep the iPhone app open" else "Not connected",
                fontSize = 10.sp,
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun SettingsPage() {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Text(text = "Settings") }
            item {
                Button(
                    onClick = { link.cycleHapticsLevel() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Haptics level", maxLines = 1) },
                    secondaryLabel = {
                        Text(text = hapticNames[link.hapticsLevel.coerceIn(0, 2)])
                    }
                )
            }
            item {
                Button(
                    onClick = {
                        if (link.hapticsOn) {
                            link.changeHapticsOn(false)
                        } else if (hasMic()) {
                            link.changeHapticsOn(true)
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Music haptics", maxLines = 1) },
                    secondaryLabel = { Text(text = if (link.hapticsOn) "On (uses mic)" else "Off") }
                )
            }
            item {
                Button(
                    onClick = { link.changeRingOnWatch(!link.ringOnWatch) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Ring on watch", maxLines = 1) },
                    secondaryLabel = { Text(text = if (link.ringOnWatch) "On" else "Off") }
                )
            }
            item {
                Button(
                    onClick = { link.changeBezelCalls(!link.bezelCalls) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = "Bezel answers calls", maxLines = 1) },
                    secondaryLabel = { Text(text = if (link.bezelCalls) "On" else "Off") }
                )
            }
            item {
                Text(
                    text = "Haptics need the watch to hear the music (speaker).",
                    fontSize = 9.sp,
                    color = Color(0xFF9E9E9E),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    // ---------- Calls ----------

    @Composable
    private fun CallScreen() {
        val focusRequester = remember { FocusRequester() }
        var acc by remember { mutableFloatStateOf(0f) }
        val shownAt = remember(link.callTick) { SystemClock.uptimeMillis() }
        var done by remember(link.callTick) { mutableStateOf(false) }
        val canAnswer = link.callPositive.isNotEmpty()
        val canDecline = link.callNegative.isNotEmpty()

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1B3A2B), Color(0xFF141416))))
                .onRotaryScrollEvent { event ->
                    if (!link.bezelCalls || done || SystemClock.uptimeMillis() - shownAt < 800L) {
                        return@onRotaryScrollEvent true
                    }
                    acc += event.verticalScrollPixels
                    if (acc >= 24f && canAnswer) {
                        done = true
                        acc = 0f
                        link.answerCall()
                    } else if (acc <= -24f && canDecline) {
                        done = true
                        acc = 0f
                        link.declineCall()
                    } else if (abs(acc) > 60f) {
                        acc = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "Incoming call", fontSize = 11.sp, color = Color(0xFFA5D6A7))
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = link.callName,
                    fontSize = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (link.callDetail.isNotEmpty()) {
                    Text(
                        text = link.callDetail,
                        fontSize = 10.sp,
                        color = Color(0xFFBBBBBB),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (canDecline) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFC62828))
                                    .clickable {
                                        done = true
                                        link.declineCall()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "✕", fontSize = 20.sp)
                            }
                            Text(text = link.callNegative, fontSize = 9.sp, color = Color(0xFFEF9A9A), maxLines = 1)
                        }
                    }
                    if (canAnswer) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2E7D32))
                                    .clickable {
                                        done = true
                                        link.answerCall()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "✓", fontSize = 20.sp)
                            }
                            Text(text = link.callPositive, fontSize = 9.sp, color = Color(0xFFA5D6A7), maxLines = 1)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (link.bezelCalls) {
                    Text(
                        text = "bezel right = ${if (canAnswer) link.callPositive else "-"}   left = ${if (canDecline) link.callNegative else "-"}",
                        fontSize = 8.sp,
                        color = Color(0xFF8A8A8E),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 38.dp, bottom = 30.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (link.ringOnWatch) Color(0xFF244B2F) else Color(0xFF3C3C40))
                    .border(1.5.dp, if (link.ringOnWatch) Color(0xFF4CAF50) else Color(0xFF58585E), CircleShape)
                    .clickable { link.changeRingOnWatch(!link.ringOnWatch) },
                contentAlignment = Alignment.Center
            ) {
                Text(text = if (link.ringOnWatch) "🔔" else "🔕", fontSize = 13.sp)
            }
        }
    }

    // ---------- Contacts ----------

    @Composable
    private fun ContactsScreen() {
        @Suppress("UNUSED_VARIABLE")
        val version = link.contactsVersion
        val list = link.contactBook.entries
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2B2B2E)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Text(text = "Contacts (${list.size})") }
            if (list.isEmpty()) {
                item {
                    Text(
                        text = "No contacts yet.\nTurn on Contacts sync in the iPhone app.",
                        fontSize = 11.sp,
                        color = Color(0xFFBBBBBB),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
            } else {
                items(list) { entry ->
                    Button(
                        onClick = { },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(text = entry.number, maxLines = 1) }
                    )
                }
            }
        }
    }

    // ---------- Music ----------

    @Composable
    private fun GlyphButton(glyph: Glyph, iconSize: Dp, filled: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(iconSize + 20.dp)
                .clip(CircleShape)
                .then(if (filled) Modifier.background(Color(0xFFFF6B6B)) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(iconSize)) {
                val w = size.width
                val h = size.height
                val tint = Color.White
                when (glyph) {
                    Glyph.PLAY -> {
                        val p = Path().apply {
                            moveTo(w * 0.25f, h * 0.12f)
                            lineTo(w * 0.9f, h * 0.5f)
                            lineTo(w * 0.25f, h * 0.88f)
                            close()
                        }
                        drawPath(p, tint)
                    }
                    Glyph.PAUSE -> {
                        drawRect(tint, Offset(w * 0.2f, h * 0.12f), Size(w * 0.2f, h * 0.76f))
                        drawRect(tint, Offset(w * 0.6f, h * 0.12f), Size(w * 0.2f, h * 0.76f))
                    }
                    Glyph.NEXT -> {
                        val p = Path().apply {
                            moveTo(w * 0.1f, h * 0.18f)
                            lineTo(w * 0.68f, h * 0.5f)
                            lineTo(w * 0.1f, h * 0.82f)
                            close()
                        }
                        drawPath(p, tint)
                        drawRect(tint, Offset(w * 0.74f, h * 0.18f), Size(w * 0.16f, h * 0.64f))
                    }
                    Glyph.PREV -> {
                        val p = Path().apply {
                            moveTo(w * 0.9f, h * 0.18f)
                            lineTo(w * 0.32f, h * 0.5f)
                            lineTo(w * 0.9f, h * 0.82f)
                            close()
                        }
                        drawPath(p, tint)
                        drawRect(tint, Offset(w * 0.1f, h * 0.18f), Size(w * 0.16f, h * 0.64f))
                    }
                }
            }
        }
    }

    @Composable
    private fun HapticsButton(modifier: Modifier) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (link.hapticsOn) Color(0xFF244B2F) else Color(0xFF3C3C40))
                    .border(1.5.dp, if (link.hapticsOn) Color(0xFF4CAF50) else Color(0xFF58585E), CircleShape)
                    .clickable { toggleHaptics() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "📳", fontSize = 13.sp)
            }
            if (link.hapticsOn) {
                Text(text = "👂", fontSize = 12.sp)
                Text(
                    text = listOf("S", "M", "H")[link.hapticsLevel.coerceIn(0, 2)],
                    fontSize = 9.sp,
                    color = Color(0xFFA5D6A7)
                )
            }
        }
    }

    @Composable
    private fun MusicScreen() {
        val focusRequester = remember { FocusRequester() }
        var acc by remember { mutableFloatStateOf(0f) }
        var lastAction by remember { mutableLongStateOf(0L) }
        var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            link.sendEvent("MR")
        }
        LaunchedEffect(Unit) {
            while (true) {
                nowMs = SystemClock.elapsedRealtime()
                delay(500)
            }
        }

        val hasTrack = link.musicTitle.isNotEmpty()
        val elapsed = if (link.musicPlaying) ((nowMs - link.musicStamp) / 1000L).toInt() else 0
        val rawPosition = (link.musicPositionSec + elapsed).coerceAtLeast(0)
        val position = if (link.musicDurationSec > 0) minOf(rawPosition, link.musicDurationSec) else rawPosition
        val fraction = if (link.musicDurationSec > 0) {
            (position.toFloat() / link.musicDurationSec.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF3B2A4A), Color(0xFF16161A))))
                .onRotaryScrollEvent { event ->
                    acc += event.verticalScrollPixels
                    if (abs(acc) >= 10f) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastAction >= 250L) {
                            link.sendEvent(if (acc > 0f) "MN" else "MV")
                            lastAction = now
                        }
                        acc = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput("musicTap") {
                    detectTapGestures(onDoubleTap = { link.sendEvent("MP") })
                }
                .pointerInput("musicVolume") {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            total += dragAmount
                            if (abs(total) >= 36f) {
                                link.sendEvent(if (total < 0f) "VU" else "VD")
                                total = 0f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (!hasTrack) {
                val message = when {
                    link.connectedAddress == null -> "Not connected"
                    !link.amsReady && !link.phoneMusic -> "Media link: " + link.amsStatus
                    else -> "Nothing playing.\nStart a song in any music app on the iPhone."
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 30.dp)
                ) {
                    Text(text = "♪", fontSize = 34.sp, color = Color(0xFFBBBBBB))
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = Color(0xFFCCCCCC),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 26.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF3A3A40)),
                        contentAlignment = Alignment.Center
                    ) {
                        val art = link.musicArt
                        if (art != null) {
                            Image(
                                bitmap = art.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(text = "♪", fontSize = 26.sp, color = Color(0xFF9E9E9E))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = link.musicTitle,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = link.musicArtist,
                        fontSize = 11.sp,
                        color = Color(0xFFBBBBBB),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF55555C))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(Color(0xFFFF6B6B))
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        GlyphButton(Glyph.PREV, 16.dp, false) { link.sendEvent("MV") }
                        GlyphButton(if (link.musicPlaying) Glyph.PAUSE else Glyph.PLAY, 18.dp, true) {
                            link.sendEvent("MP")
                        }
                        GlyphButton(Glyph.NEXT, 16.dp, false) { link.sendEvent("MN") }
                    }
                    Text(
                        text = "bezel: skip   swipe: volume",
                        fontSize = 8.sp,
                        color = Color(0xFF8A8A8E)
                    )
                }
            }
            HapticsButton(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 34.dp, bottom = 24.dp)
            )
        }
    }

    // ---------- Gallery touchpad ----------

    @Composable
    private fun PadScreen(zoom: Boolean) {
        val focusRequester = remember { FocusRequester() }
        var rotaryAcc by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF4A4A4A))
                .onRotaryScrollEvent { event ->
                    rotaryAcc += event.verticalScrollPixels
                    if (abs(rotaryAcc) >= 20f) {
                        link.sendEvent(if (rotaryAcc > 0f) "B 1" else "B -1")
                        rotaryAcc = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(zoom) {
                    if (zoom) {
                        detectTapGestures(onDoubleTap = { link.sendEvent("ZD") })
                    } else {
                        detectTapGestures(onTap = { offset ->
                            link.sendEvent("T ${norm(offset.x, size.width)} ${norm(offset.y, size.height)}")
                        })
                    }
                }
                .pointerInput(Pair(zoom, 2)) {
                    if (zoom) {
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragStart = { acc = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                acc += dragAmount
                                if (abs(acc) >= 8f) {
                                    link.sendEvent("Z ${(-acc).toInt()}")
                                    acc = 0f
                                }
                            }
                        )
                    } else {
                        var lastMove = 0L
                        detectDragGestures(
                            onDragStart = { offset ->
                                link.sendEvent("M ${norm(offset.x, size.width)} ${norm(offset.y, size.height)}")
                            },
                            onDrag = { change, _ ->
                                val now = SystemClock.uptimeMillis()
                                if (now - lastMove >= 40L) {
                                    lastMove = now
                                    link.sendEvent(
                                        "M ${norm(change.position.x, size.width)} ${norm(change.position.y, size.height)}"
                                    )
                                }
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (zoom) "Drag up / down or turn the bezel to zoom.\nDouble tap when done." else "Touchpad\nSlide to choose, tap to open.\nBezel scrolls.\nBack button leaves the gallery.",
                fontSize = 12.sp,
                color = Color(0xFFCCCCCC),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 30.dp)
            )
        }
    }

    @Composable
    private fun ViewerScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2B2B2E))
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { link.sendEvent("ZM") },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Zoom") }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = { link.sendEvent("ED") },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "Edit") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Back to gallery",
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { link.sendEvent("BK") }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }

    // ---------- Editor ----------

    @Composable
    private fun EditScreen() {
        val pagerState = rememberPagerState(pageCount = { attributeNames.size })
        val focusRequester = remember { FocusRequester() }
        var saveState by remember { mutableIntStateOf(0) }
        val startSaved = remember { link.savedTick }
        val startFailed = remember { link.saveFailTick }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        LaunchedEffect(pagerState.currentPage) {
            link.sendEvent("A ${pagerState.currentPage}")
        }
        LaunchedEffect(link.editSession) {
            for (k in editValues.indices) {
                editValues[k] = 0
            }
        }
        LaunchedEffect(link.savedTick) {
            if (link.savedTick > startSaved) {
                saveState = 2
                delay(2000)
                saveState = 0
            }
        }
        LaunchedEffect(link.saveFailTick) {
            if (link.saveFailTick > startFailed) {
                saveState = 3
                delay(3500)
                saveState = 0
            }
        }
        LaunchedEffect(saveState) {
            if (saveState == 1) {
                delay(8000)
                if (saveState == 1) {
                    saveState = 0
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF232326))
                .onRotaryScrollEvent { event ->
                    val i = pagerState.currentPage
                    val dy = event.verticalScrollPixels
                    val dir = if (dy > 0f) 1 else if (dy < 0f) -1 else 0
                    if (dir != 0) {
                        val next = (editValues[i] + dir * 2).coerceIn(-100, 100)
                        if (next != editValues[i]) {
                            editValues[i] = next
                            requestSend(i)
                        }
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        val i = pagerState.currentPage
                        editValues[i] = 0
                        requestSend(i)
                    })
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                AttributePage(page)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 36.dp, top = 36.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        when (saveState) {
                            2 -> Color(0xFF2E7D32)
                            3 -> Color(0xFFB71C1C)
                            else -> Color(0xFF3C3C40)
                        }
                    )
                    .border(1.5.dp, Color(0xFF4CAF50), CircleShape)
                    .clickable {
                        saveState = 1
                        link.sendEvent("SV")
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (saveState) {
                        1 -> "..."
                        2 -> "✓"
                        3 -> "✗"
                        else -> "Save"
                    },
                    fontSize = if (saveState == 0) 10.sp else 16.sp
                )
            }

            Text(
                text = when (saveState) {
                    1 -> "saving..."
                    2 -> "saved to Photos"
                    3 -> "save failed - see iPhone"
                    else -> "${pagerState.currentPage + 1}/${attributeNames.size}"
                },
                fontSize = 10.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            )
        }
    }

    @Composable
    private fun AttributePage(page: Int) {
        val value = editValues[page]
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = attributeNames[page], fontSize = 15.sp, color = Color(0xFFBBBBBB))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = if (value > 0) "+$value" else "$value", fontSize = 40.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .width(110.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF4A4A4E))
            ) {
                Box(
                    modifier = Modifier
                        .width(55.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (value < 0) {
                        Box(
                            modifier = Modifier
                                .width((55f * abs(value) / 100f).dp)
                                .fillMaxHeight()
                                .background(Color(0xFFFFB74D))
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .width(55.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value > 0) {
                        Box(
                            modifier = Modifier
                                .width((55f * value / 100f).dp)
                                .fillMaxHeight()
                                .background(Color(0xFF64B5F6))
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "bezel changes it. double tap resets.",
                fontSize = 9.sp,
                color = Color(0xFF8A8A8E),
                textAlign = TextAlign.Center
            )
        }
    }
}
