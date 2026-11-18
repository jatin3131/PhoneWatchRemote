package com.example.phoneremotewatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/** Makes the watch visible to the iPhone app over Bluetooth Low Energy. */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context) {

    companion object {
        /** Must match the service UUID in the iPhone app. */
        val SERVICE_UUID: UUID = UUID.fromString("6E5A0001-B5A3-F393-E0A9-E50E24DCCA9E")
    }

    var status by mutableStateOf("Not started")
    var connectedAddress by mutableStateOf<String?>(null)
    var advertising by mutableStateOf(false)
    var connectCount by mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

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
        gattServer.addService(
            BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        )
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
        advertising = false
        connectedAddress = null
    }
}
