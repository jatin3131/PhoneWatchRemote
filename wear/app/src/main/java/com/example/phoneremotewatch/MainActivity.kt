package com.example.phoneremotewatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlin.math.abs

private fun norm(value: Float, total: Int): Int {
    if (total <= 0) return 0
    return ((value / total.toFloat()) * 999f).toInt().coerceIn(0, 999)
}

class MainActivity : ComponentActivity() {

    private lateinit var link: BleLink

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasBlePermissions()) {
                link.start()
            } else {
                link.status = "Bluetooth permission was denied"
            }
        }

    private fun requiredPermissions(): Array<String> {
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
        return requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startLink() {
        if (hasBlePermissions()) {
            link.start()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = BleLink(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                AppScreen()
            }
        }
        startLink()
    }

    override fun onDestroy() {
        link.stop()
        super.onDestroy()
    }

    @Composable
    private fun AppScreen() {
        when (link.mode) {
            WatchMode.IDLE -> StatusScreen()
            WatchMode.GALLERY -> PadScreen(zoom = false)
            WatchMode.ZOOM -> PadScreen(zoom = true)
            WatchMode.VIEWER -> ViewerScreen()
        }
    }

    @Composable
    private fun StatusScreen() {
        val address = link.connectedAddress
        val dot = when {
            address != null -> Color(0xFF4CAF50)
            link.advertising -> Color(0xFFFFC107)
            else -> Color(0xFF9E9E9E)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Phone Remote", fontSize = 14.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = link.status,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (link.dataReady) "data link: ready" else "data link: not ready",
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = { startLink() },
                modifier = Modifier.fillMaxWidth(0.8f),
                label = { Text(text = "Restart") }
            )
        }
    }

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
                text = if (zoom) "Drag up / down or turn the bezel to zoom.\nDouble tap when done." else "Touchpad\nSlide to choose, tap to open.\nBezel scrolls.",
                fontSize = 12.sp,
                color = Color(0xFFBBBBBB),
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
}
