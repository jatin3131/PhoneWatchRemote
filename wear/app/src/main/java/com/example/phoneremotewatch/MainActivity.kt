package com.example.phoneremotewatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

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
                StatusScreen()
            }
        }
        startLink()
    }

    override fun onDestroy() {
        link.stop()
        super.onDestroy()
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
            if (address != null) {
                Text(
                    text = "connected $address",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = "connections: ${link.connectCount}",
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
}
