package com.example.phoneremotewatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlin.math.roundToInt

/** Collects heart rate, steps and other daily numbers from the watch (low power, in the background). */
class HealthSource(private val context: Context, private val onData: () -> Unit) {

    var heartRate = -1
    var hrTimeMs = 0L
    var steps = -1L
    var calories = -1.0
    var distance = -1.0
    var floors = -1.0
    var status = "not started"
    private var registered = false

    private val callback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val hr = dataPoints.getData(DataType.HEART_RATE_BPM)
            if (hr.isNotEmpty()) {
                heartRate = hr.last().value.roundToInt()
                hrTimeMs = System.currentTimeMillis()
            }
            val st = dataPoints.getData(DataType.STEPS_DAILY)
            if (st.isNotEmpty()) steps = st.last().value
            val ca = dataPoints.getData(DataType.CALORIES_DAILY)
            if (ca.isNotEmpty()) calories = ca.last().value
            val di = dataPoints.getData(DataType.DISTANCE_DAILY)
            if (di.isNotEmpty()) distance = di.last().value
            val fl = dataPoints.getData(DataType.FLOORS_DAILY)
            if (fl.isNotEmpty()) floors = fl.last().value
            status = "receiving"
            onData()
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            status = "registration failed: ${throwable.message}"
            registered = false
            onData()
        }

        override fun onPermissionLost() {
            status = "permission lost"
            registered = false
            onData()
        }
    }

    private fun hrPermission(): String {
        return if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEART_RATE"
        } else {
            Manifest.permission.BODY_SENSORS
        }
    }

    fun hasHeartPermission(): Boolean {
        return context.checkSelfPermission(hrPermission()) == PackageManager.PERMISSION_GRANTED
    }

    fun hasActivityPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }

    fun configure(mask: Int) {
        val types = HashSet<DataType<*, *>>()
        if ((mask and 2) != 0 && hasHeartPermission()) types.add(DataType.HEART_RATE_BPM)
        if (hasActivityPermission()) {
            if ((mask and 4) != 0) types.add(DataType.STEPS_DAILY)
            if ((mask and 8) != 0) types.add(DataType.CALORIES_DAILY)
            if ((mask and 16) != 0) types.add(DataType.DISTANCE_DAILY)
            if ((mask and 32) != 0) types.add(DataType.FLOORS_DAILY)
        }
        if (types.isEmpty()) {
            stop()
            status = if (mask and 62 == 0) "only battery selected" else "allow health access (tap the heart bubble)"
            return
        }
        try {
            val client = HealthServices.getClient(context).passiveMonitoringClient
            val config = PassiveListenerConfig.builder().setDataTypes(types).build()
            client.setPassiveListenerCallback(config, callback)
            registered = true
            status = "registered, waiting for data"
        } catch (e: Exception) {
            status = "could not start: ${e.message}"
        }
    }

    fun stop() {
        if (!registered) return
        try {
            HealthServices.getClient(context).passiveMonitoringClient.clearPassiveListenerCallbackAsync()
        } catch (e: Exception) {
            // Nothing to stop.
        }
        registered = false
    }
}
