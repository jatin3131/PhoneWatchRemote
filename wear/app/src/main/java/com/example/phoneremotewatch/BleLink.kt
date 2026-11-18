package com.example.phoneremotewatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

enum class WatchMode { IDLE, GALLERY, VIEWER, ZOOM }

/** Makes the watch visible to the iPhone app and carries messages both ways. */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context) {

    companion object {
        /** These must match the UUIDs in the iPhone app. */
        val SERVICE_UUID: UUID = UUID.fromString("6E5A0001-B5A3-F393-E0A9-E50E24DCCA9E")
        val EVENT_UUID: UUID = UUID.fromString("6E5A0002-B5A3-F393-E0A9-E50E24DCCA9E")
        val STATE_UUID: UUID = UUID.fromString("6E5A0003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    var status by mutableStateOf("Not started")
    var connectedAddress by mutableStateOf<String?>(null)
    var advertising by mutableStateOf(false)
    var connectCount by mutableIntStateOf(0)
    var dataReady by mutableStateOf(false)
    var mode by mutableStateOf(WatchMode.IDLE)

    private val main = Handler(Looper.getMainLooper())
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var eventChar: BluetoothGattCharacteristic? = null
    private var subscribedDevice: BluetoothDevice? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            main.post {
                advertising = true
                if (connectedAddress == null) {
                    status = "Waiting for the iPhone. Scan on the iPhone and tap this watch."
                }
            }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                advertising = false
                status = "Advertising failed (code $errorCode)"
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, gattStatus: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedAddress = device.address
                    connectCount += 1
                    status = "iPhone connected"
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedAddress = null
                    subscribedDevice = null
                    dataReady = false
                    mode = WatchMode.IDLE
                    status = "iPhone disconnected. Waiting again..."
                    startAdvertising()
                }
            }
        }

        override fun onServiceAdded(gattStatus: Int, service: BluetoothGattService) {
            main.post {
                if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                    startAdvertising()
                } else {
                    status = "Could not add Bluetooth service ($gattStatus)"
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value.isNotEmpty() && value[0].toInt() != 0
                main.post {
                    subscribedDevice = if (enabled) device else null
                    dataReady = enabled
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == STATE_UUID) {
                val text = String(value, Charsets.UTF_8)
                main.post { handleState(text) }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private fun handleState(text: String) {
        when (text.trim()) {
            "G" -> mode = WatchMode.GALLERY
            "V" -> mode = WatchMode.VIEWER
            "Z" -> mode = WatchMode.ZOOM
            "I" -> mode = WatchMode.IDLE
        }
    }

    fun start() {
        stop()
        val mgr = manager
        if (mgr == null) {
            status = "No Bluetooth on this watch"
            return
        }
        val adapter = mgr.adapter
        if (adapter == null) {
            status = "No Bluetooth on this watch"
            return
        }
        if (!adapter.isEnabled) {
            status = "Bluetooth is off. Turn it on in the watch settings, then press Restart."
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            status = "This watch cannot advertise over Bluetooth LE"
            return
        }
        advertiser = adv
        val gattServer = mgr.openGattServer(context, serverCallback)
        if (gattServer == null) {
            status = "Could not open the Bluetooth server"
            return
        }
        server = gattServer

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val event = BluetoothGattCharacteristic(
            EVENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        event.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        val state = BluetoothGattCharacteristic(
            STATE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(event)
        service.addCharacteristic(state)
        eventChar = event
        gattServer.addService(service)
        status = "Starting..."
    }

    private fun startAdvertising() {
        val adv = advertiser ?: return
        try {
            adv.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // Nothing to stop.
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    /** Sends a short message to the iPhone (must stay under 20 bytes). */
    fun sendEvent(text: String) {
        val srv = server ?: return
        val dev = subscribedDevice ?: return
        val ch = eventChar ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(dev, ch, false, bytes)
            } else {
                ch.value = bytes
                srv.notifyCharacteristicChanged(dev, ch, false)
            }
        } catch (e: Exception) {
            // The iPhone may have just disconnected.
        }
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // Ignore.
        }
        try {
            server?.close()
        } catch (e: Exception) {
            // Ignore.
        }
        server = null
        advertiser = null
        eventChar = null
        subscribedDevice = null
        advertising = false
        connectedAddress = null
        dataReady = false
        mode = WatchMode.IDLE
    }
}
