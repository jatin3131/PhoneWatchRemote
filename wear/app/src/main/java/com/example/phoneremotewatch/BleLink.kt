package com.example.phoneremotewatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.util.UUID

enum class WatchMode { IDLE, GALLERY, VIEWER, ZOOM, EDIT }

/** Talks to the iPhone app, Apple's media service (any music app) and Apple's notification service (calls). */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context) {

    companion object {
        /** These must match the UUIDs in the iPhone app. */
        val SERVICE_UUID: UUID = UUID.fromString("6E5A0001-B5A3-F393-E0A9-E50E24DCCA9E")
        val EVENT_UUID: UUID = UUID.fromString("6E5A0002-B5A3-F393-E0A9-E50E24DCCA9E")
        val STATE_UUID: UUID = UUID.fromString("6E5A0003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Apple Media Service. */
        val AMS_SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
        val AMS_REMOTE: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
        val AMS_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")

        /** Apple Notification Center Service. */
        val ANCS_SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val ANCS_NOTIF: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val ANCS_CONTROL: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val ANCS_DATA: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    }

    private val prefs = context.getSharedPreferences("watch_settings", Context.MODE_PRIVATE)

    var status by mutableStateOf("Not started")
    var connectedAddress by mutableStateOf<String?>(null)
    var advertising by mutableStateOf(false)
    var connectCount by mutableIntStateOf(0)
    var dataReady by mutableStateOf(false)
    var mode by mutableStateOf(WatchMode.IDLE)
    var phoneGallery by mutableStateOf(false)
    var phoneMusic by mutableStateOf(false)
    var savedTick by mutableIntStateOf(0)
    var saveFailTick by mutableIntStateOf(0)
    var editSession by mutableIntStateOf(0)

    var musicTitle by mutableStateOf("")
    var musicArtist by mutableStateOf("")
    var musicPlaying by mutableStateOf(false)
    var musicDurationSec by mutableIntStateOf(0)
    var musicPositionSec by mutableIntStateOf(0)
    var musicStamp by mutableLongStateOf(0L)
    var musicArt by mutableStateOf<Bitmap?>(null)

    var amsStatus by mutableStateOf("not started")
    var amsReady by mutableStateOf(false)
    var ancsStatus by mutableStateOf("not started")
    var ancsReady by mutableStateOf(false)

    var callActive by mutableStateOf(false)
    var callName by mutableStateOf("")
    var callDetail by mutableStateOf("")
    var callPositive by mutableStateOf("")
    var callNegative by mutableStateOf("")
    var callTick by mutableIntStateOf(0)

    val contactBook = ContactBook(context)
    var contactCount by mutableIntStateOf(0)
    var contactsVersion by mutableIntStateOf(0)

    var shareCalls by mutableStateOf(true)
    var shareContacts by mutableStateOf(true)
    var hapticsOn by mutableStateOf(prefs.getBoolean("haptics_on", false))
    var hapticsLevel by mutableIntStateOf(prefs.getInt("haptics_level", 1))
    var ringOnWatch by mutableStateOf(prefs.getBoolean("ring_watch", true))
    var bezelCalls by mutableStateOf(prefs.getBoolean("bezel_calls", true))

    var running = false
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var eventChar: BluetoothGattCharacteristic? = null
    private var subscribedDevice: BluetoothDevice? = null

    private var asmActive = false
    private var asmType = 0
    private var asmTotal = 0
    private var asmBuf = ByteArrayOutputStream()

    private var appTitle = ""
    private var appArt: Bitmap? = null
    private val artCache = HashMap<String, Bitmap>()
    private var lastArtKey = ""

    private var phoneDevice: BluetoothDevice? = null
    private var clientGatt: BluetoothGatt? = null
    private var amsRemoteChar: BluetoothGattCharacteristic? = null
    private var amsUpdateChar: BluetoothGattCharacteristic? = null
    private var ancsControlChar: BluetoothGattCharacteristic? = null
    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var amsRetries = 0
    private var receiverRegistered = false

    private val notifyQueue = ArrayDeque<ByteArray>()
    private var notifyBusy = false
    private var notifyBusySince = 0L

    private val ancsQueue = ArrayDeque<Long>()
    private var ancsFetching = false
    private var ancsFetchUid = 0L
    private var ancsBuf = ByteArrayOutputStream()
    private var callUid = -1L

    init {
        contactCount = contactBook.load()
    }

    private fun changed() {
        listener?.invoke()
    }

    // ---------- Saved settings ----------

    fun changeHapticsOn(value: Boolean) {
        hapticsOn = value
        prefs.edit().putBoolean("haptics_on", value).apply()
        changed()
    }

    fun cycleHapticsLevel() {
        hapticsLevel = (hapticsLevel + 1) % 3
        prefs.edit().putInt("haptics_level", hapticsLevel).apply()
        changed()
    }

    fun changeRingOnWatch(value: Boolean) {
        ringOnWatch = value
        prefs.edit().putBoolean("ring_watch", value).apply()
        changed()
    }

    fun changeBezelCalls(value: Boolean) {
        bezelCalls = value
        prefs.edit().putBoolean("bezel_calls", value).apply()
    }

    private fun clearMusic() {
        musicTitle = ""
        musicArtist = ""
        musicPlaying = false
        musicDurationSec = 0
        musicPositionSec = 0
        musicArt = null
        appTitle = ""
        appArt = null
        lastArtKey = ""
    }

    private fun clearCall() {
        callActive = false
        callUid = -1L
        callName = ""
        callDetail = ""
        callPositive = ""
        callNegative = ""
        changed()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            main.post {
                advertising = true
                if (connectedAddress == null) {
                    status = "Waiting for the iPhone. Scan on the iPhone and tap this watch."
                    changed()
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
                    phoneDevice = device
                    amsRetries = 0
                    amsStatus = "waiting to connect..."
                    ancsStatus = "waiting to connect..."
                    changed()
                    main.postDelayed({ startAms() }, 2500L)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedAddress = null
                    subscribedDevice = null
                    dataReady = false
                    mode = WatchMode.IDLE
                    phoneGallery = false
                    phoneMusic = false
                    notifyQueue.clear()
                    notifyBusy = false
                    stopAmsClient()
                    phoneDevice = null
                    amsStatus = "iPhone disconnected"
                    ancsStatus = "iPhone disconnected"
                    clearMusic()
                    callActive = false
                    callUid = -1L
                    status = "iPhone disconnected. Waiting again..."
                    changed()
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

        override fun onNotificationSent(device: BluetoothDevice, gattStatus: Int) {
            main.post {
                notifyBusy = false
                pumpNotify()
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
                handleWrite(value)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    // ---------- Messages from the iPhone app ----------

    /** Short text messages start with a letter. Long messages are sent in numbered pieces. */
    @Synchronized
    private fun handleWrite(value: ByteArray) {
        if (value.isEmpty()) return
        val first = value[0].toInt()
        if (first == 1) {
            if (value.size < 6) return
            asmType = value[1].toInt() and 0xFF
            asmTotal = ((value[2].toInt() and 0xFF) shl 24) or
                ((value[3].toInt() and 0xFF) shl 16) or
                ((value[4].toInt() and 0xFF) shl 8) or
                (value[5].toInt() and 0xFF)
            asmBuf = ByteArrayOutputStream()
            asmBuf.write(value, 6, value.size - 6)
            asmActive = true
            finishIfComplete()
        } else if (first == 2) {
            if (!asmActive) return
            asmBuf.write(value, 1, value.size - 1)
            finishIfComplete()
        } else {
            val text = String(value, Charsets.UTF_8)
            main.post { handleState(text) }
        }
    }

    private fun finishIfComplete() {
        if (!asmActive) return
        if (asmBuf.size() >= asmTotal) {
            val all = asmBuf.toByteArray()
            val payload = if (all.size > asmTotal) all.copyOfRange(0, asmTotal) else all
            val type = asmType
            asmActive = false
            main.post { applyFrame(type, payload) }
        }
    }

    private fun titlesMatch(): Boolean {
        return appTitle.isNotEmpty() && appTitle.trim().equals(musicTitle.trim(), ignoreCase = true)
    }

    private fun applyFrame(type: Int, payload: ByteArray) {
        when (type) {
            0x4D -> {
                val lines = String(payload, Charsets.UTF_8).split("\n", limit = 5)
                appTitle = lines.getOrNull(3) ?: ""
                if (!amsReady) {
                    musicPlaying = lines.getOrNull(0) == "1"
                    musicDurationSec = lines.getOrNull(1)?.toIntOrNull() ?: 0
                    musicPositionSec = lines.getOrNull(2)?.toIntOrNull() ?: 0
                    musicStamp = SystemClock.elapsedRealtime()
                    musicTitle = appTitle
                    musicArtist = lines.getOrNull(4) ?: ""
                }
            }
            0x41 -> {
                appArt = if (payload.isEmpty()) {
                    null
                } else {
                    BitmapFactory.decodeByteArray(payload, 0, payload.size)
                }
                if (!amsReady || titlesMatch()) {
                    musicArt = appArt
                }
            }
            0x42 -> {
                val nl = payload.indexOf(10.toByte())
                if (nl > 0) {
                    val title = String(payload, 0, nl, Charsets.UTF_8).trim().lowercase()
                    val imgLen = payload.size - nl - 1
                    if (imgLen > 0) {
                        val bmp = BitmapFactory.decodeByteArray(payload, nl + 1, imgLen)
                        if (bmp != null) {
                            if (artCache.size > 30) artCache.clear()
                            artCache[title] = bmp
                            if (title == musicTitle.trim().lowercase() && !titlesMatch()) {
                                musicArt = bmp
                            }
                        }
                    }
                }
            }
            0x43 -> {
                if (shareContacts) {
                    contactCount = contactBook.replaceFrom(String(payload, Charsets.UTF_8))
                    contactsVersion += 1
                }
            }
        }
        changed()
    }

    private fun handleState(text: String) {
        val parts = text.trim().split(" ")
        when (parts[0]) {
            "G" -> mode = WatchMode.GALLERY
            "V" -> mode = WatchMode.VIEWER
            "Z" -> mode = WatchMode.ZOOM
            "X" -> {
                mode = WatchMode.EDIT
                editSession += 1
            }
            "I" -> mode = WatchMode.IDLE
            "SD" -> savedTick += 1
            "SF" -> saveFailTick += 1
            "P" -> {
                phoneGallery = parts.getOrNull(1) == "1"
                phoneMusic = parts.getOrNull(2) == "1"
            }
            "S" -> {
                shareCalls = parts.getOrNull(1) != "0"
                val contactsOn = parts.getOrNull(2) != "0"
                if (!contactsOn && shareContacts) {
                    contactBook.clear()
                    contactCount = 0
                    contactsVersion += 1
                }
                shareContacts = contactsOn
                if (!shareCalls && callActive) {
                    clearCall()
                }
            }
        }
        changed()
    }

    // ---------- Sending to the iPhone app (one packet at a time) ----------

    private fun enqueueNotify(bytes: ByteArray, coalesce: Boolean) {
        if (notifyQueue.size > 300) notifyQueue.clear()
        if (coalesce && notifyQueue.isNotEmpty()) {
            val last = notifyQueue.last()
            if (last.size >= 2 && bytes.size >= 2 && last[0] == bytes[0] && last[1] == bytes[1]) {
                notifyQueue.removeLast()
            }
        }
        notifyQueue.addLast(bytes)
        pumpNotify()
    }

    private fun pumpNotify() {
        if (notifyBusy && SystemClock.elapsedRealtime() - notifyBusySince > 400L) {
            notifyBusy = false
        }
        if (notifyBusy) return
        val srv = server ?: return
        val dev = subscribedDevice ?: return
        val ch = eventChar ?: return
        val bytes = notifyQueue.removeFirstOrNull() ?: return
        try {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(dev, ch, false, bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.value = bytes
                srv.notifyCharacteristicChanged(dev, ch, false)
            }
            if (ok) {
                notifyBusy = true
                notifyBusySince = SystemClock.elapsedRealtime()
            } else {
                notifyQueue.addFirst(bytes)
                main.postDelayed({ pumpNotify() }, 20L)
            }
        } catch (e: Exception) {
            // The iPhone may have just disconnected.
        }
    }

    /** Sends a longer message to the iPhone app in small numbered pieces. */
    fun sendFrame(type: Int, payload: ByteArray) {
        val total = payload.size
        val first = ByteArrayOutputStream()
        first.write(1)
        first.write(type)
        first.write((total shr 24) and 0xFF)
        first.write((total shr 16) and 0xFF)
        first.write((total shr 8) and 0xFF)
        first.write(total and 0xFF)
        val firstCount = minOf(14, total)
        first.write(payload, 0, firstCount)
        enqueueNotify(first.toByteArray(), false)
        var offset = firstCount
        while (offset < total) {
            val n = minOf(19, total - offset)
            val pkt = ByteArray(n + 1)
            pkt[0] = 2
            System.arraycopy(payload, offset, pkt, 1, n)
            enqueueNotify(pkt, false)
            offset += n
        }
    }

    // ---------- Apple Media Service and Apple Notification Center Service ----------

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val phone = phoneDevice ?: return
            if (dev.address != phone.address) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            main.post {
                if (state == BluetoothDevice.BOND_BONDED) {
                    amsStatus = "paired, starting..."
                    main.postDelayed({ startAms() }, 1500L)
                } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                    amsStatus = "pairing refused or failed. Use Restart link."
                }
            }
        }
    }

    private fun queueOp(op: () -> Boolean) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        if (busy) return
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            val started = try {
                op()
            } catch (e: Exception) {
                false
            }
            if (started) {
                busy = true
                return
            }
        }
    }

    private fun opDone() {
        busy = false
        pump()
    }

    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }
    }

    private fun writeChar(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = value
            gatt.writeCharacteristic(ch)
        }
    }

    private fun stopAmsClient() {
        ops.clear()
        busy = false
        amsReady = false
        ancsReady = false
        amsRemoteChar = null
        amsUpdateChar = null
        ancsControlChar = null
        ancsQueue.clear()
        ancsFetching = false
        try {
            clientGatt?.disconnect()
        } catch (e: Exception) {
            // Ignore.
        }
        try {
            clientGatt?.close()
        } catch (e: Exception) {
            // Ignore.
        }
        clientGatt = null
    }

    private fun startAms() {
        val device = phoneDevice ?: return
        if (connectedAddress == null) return
        stopAmsClient()
        amsStatus = "connecting to the iPhone media service..."
        try {
            clientGatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            amsStatus = "could not start: ${e.message}"
        }
    }

    private fun scheduleAmsRetry() {
        if (amsRetries >= 3) return
        amsRetries += 1
        main.postDelayed({
            if (!amsReady && connectedAddress != null) {
                startAms()
            }
        }, 20000L)
    }

    private fun needPairing() {
        ops.clear()
        busy = false
        amsStatus = "pairing needed: accept the request on the iPhone and the watch"
        val dev = phoneDevice
        if (dev != null && dev.bondState == BluetoothDevice.BOND_NONE) {
            try {
                dev.createBond()
            } catch (e: Exception) {
                // Pairing may already be running.
            }
        }
        scheduleAmsRetry()
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, gattStatus: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { amsStatus = "connected, looking for the media service..." }
                val asked = try {
                    gatt.requestMtu(185)
                } catch (e: Exception) {
                    false
                }
                if (!asked) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post {
                    if (gatt == clientGatt) {
                        amsReady = false
                        amsStatus = "media link closed (code $gattStatus)"
                        stopAmsClient()
                        if (gattStatus != 0) {
                            scheduleAmsRetry()
                        }
                    } else {
                        try {
                            gatt.close()
                        } catch (e: Exception) {
                            // Ignore.
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, gattStatus: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, gattStatus: Int) {
            main.post {
                if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
                    amsStatus = "service search failed (code $gattStatus)"
                    scheduleAmsRetry()
                    return@post
                }
                val service = gatt.getService(AMS_SERVICE)
                if (service == null) {
                    needPairing()
                    return@post
                }
                val update = service.getCharacteristic(AMS_UPDATE)
                val remote = service.getCharacteristic(AMS_REMOTE)
                if (update == null || remote == null) {
                    amsStatus = "media service found but incomplete"
                    return@post
                }
                amsUpdateChar = update
                amsRemoteChar = remote
                amsStatus = "subscribing..."
                queueOp { enableNotify(gatt, update) }
                queueOp { enableNotify(gatt, remote) }
                queueOp { writeChar(gatt, update, byteArrayOf(0, 1, 2)) }
                queueOp { writeChar(gatt, update, byteArrayOf(2, 0, 2, 3)) }

                val ancs = gatt.getService(ANCS_SERVICE)
                if (ancs == null) {
                    ancsStatus = "off: iPhone Settings > Bluetooth > (i) next to the watch > Share System Notifications"
                } else {
                    val notif = ancs.getCharacteristic(ANCS_NOTIF)
                    val control = ancs.getCharacteristic(ANCS_CONTROL)
                    val data = ancs.getCharacteristic(ANCS_DATA)
                    if (notif == null || control == null || data == null) {
                        ancsStatus = "notification service incomplete"
                    } else {
                        ancsControlChar = control
                        ancsStatus = "subscribing..."
                        queueOp { enableNotify(gatt, data) }
                        queueOp { enableNotify(gatt, notif) }
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, gattStatus: Int) {
            main.post {
                if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                    if (descriptor.characteristic.uuid == ANCS_NOTIF) {
                        ancsReady = true
                        ancsStatus = "ready"
                        changed()
                    }
                    opDone()
                } else if (gattStatus == 5 || gattStatus == 15) {
                    needPairing()
                } else {
                    ops.clear()
                    busy = false
                    amsStatus = "subscribe failed (code $gattStatus)"
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, gattStatus: Int) {
            main.post {
                if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                    if (characteristic.uuid == AMS_UPDATE && !amsReady) {
                        amsReady = true
                        amsStatus = "connected - any music app"
                        changed()
                    }
                    opDone()
                } else if (gattStatus == 5 || gattStatus == 15) {
                    needPairing()
                } else {
                    if (characteristic.uuid == ANCS_CONTROL) {
                        ancsStatus = "call request refused (code $gattStatus)"
                        ancsFetching = false
                    } else {
                        amsStatus = "media command refused (code $gattStatus)"
                    }
                    opDone()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            handleNotify(characteristic.uuid, bytes)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(characteristic.uuid, value)
        }
    }

    private fun handleNotify(uuid: UUID, bytes: ByteArray) {
        val copy = bytes.copyOf()
        when (uuid) {
            AMS_UPDATE -> {
                if (copy.size < 3) return
                val entity = copy[0].toInt() and 0xFF
                val attr = copy[1].toInt() and 0xFF
                val text = String(copy, 3, copy.size - 3, Charsets.UTF_8)
                main.post { applyAms(entity, attr, text) }
            }
            ANCS_NOTIF -> main.post { handleAncsNotification(copy) }
            ANCS_DATA -> main.post { handleAncsData(copy) }
        }
    }

    private fun applyAms(entity: Int, attr: Int, value: String) {
        if (entity == 0 && attr == 1) {
            val parts = value.split(",")
            musicPlaying = parts.getOrNull(0)?.trim() == "1"
            val elapsed = parts.getOrNull(2)?.trim()?.toDoubleOrNull() ?: 0.0
            musicPositionSec = elapsed.toInt()
            musicStamp = SystemClock.elapsedRealtime()
        } else if (entity == 2) {
            when (attr) {
                0 -> musicArtist = value
                2 -> {
                    musicTitle = value
                    requestArtwork()
                }
                3 -> musicDurationSec = (value.trim().toDoubleOrNull() ?: 0.0).toInt()
            }
            musicArt = if (titlesMatch()) appArt else artCache[musicTitle.trim().lowercase()]
        }
        changed()
    }

    /** Asks the iPhone app to look up the cover for the current song. */
    private fun requestArtwork() {
        val title = musicTitle.trim()
        if (title.isEmpty() || !dataReady) return
        val key = title.lowercase() + "|" + musicArtist.trim().lowercase()
        if (key == lastArtKey) return
        lastArtKey = key
        if (artCache.containsKey(title.lowercase())) return
        sendFrame(0x51, (title + "\n" + musicArtist.trim()).toByteArray(Charsets.UTF_8))
    }

    private fun amsCommand(id: Int) {
        val gatt = clientGatt ?: return
        val ch = amsRemoteChar ?: return
        queueOp { writeChar(gatt, ch, byteArrayOf(id.toByte())) }
    }

    // ---------- Calls (Apple Notification Center Service) ----------

    private fun readUid(b: ByteArray, off: Int): Long {
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun uidBytes(uid: Long): ByteArray {
        return byteArrayOf(
            (uid and 0xFF).toByte(),
            ((uid shr 8) and 0xFF).toByte(),
            ((uid shr 16) and 0xFF).toByte(),
            ((uid shr 24) and 0xFF).toByte()
        )
    }

    private fun handleAncsNotification(bytes: ByteArray) {
        if (bytes.size < 8) return
        val event = bytes[0].toInt() and 0xFF
        val flags = bytes[1].toInt() and 0xFF
        val category = bytes[2].toInt() and 0xFF
        val uid = readUid(bytes, 4)
        if (event == 2) {
            if (callActive && uid == callUid) {
                clearCall()
            }
            return
        }
        if (!shareCalls) return
        if (category != 1) return
        if ((flags and 4) != 0) return
        ancsEnqueue(uid)
    }

    private fun ancsEnqueue(uid: Long) {
        if (!ancsQueue.contains(uid)) {
            ancsQueue.addLast(uid)
        }
        ancsPump()
    }

    private fun ancsPump() {
        if (ancsFetching) return
        val gatt = clientGatt
        val ch = ancsControlChar
        if (gatt == null || ch == null) return
        val uid = ancsQueue.removeFirstOrNull() ?: return
        ancsFetching = true
        ancsFetchUid = uid
        ancsBuf = ByteArrayOutputStream()
        val cmd = byteArrayOf(0) + uidBytes(uid) + byteArrayOf(1, 80, 0, 2, 80, 0, 3, 80, 0, 6, 7)
        queueOp { writeChar(gatt, ch, cmd) }
        main.postDelayed({
            if (ancsFetching && ancsFetchUid == uid) {
                ancsFetching = false
                ancsPump()
            }
        }, 3000L)
    }

    private fun handleAncsData(bytes: ByteArray) {
        if (!ancsFetching) return
        ancsBuf.write(bytes, 0, bytes.size)
        val all = ancsBuf.toByteArray()
        if (all.size < 5) return
        var pos = 5
        val attrs = HashMap<Int, String>()
        while (pos + 3 <= all.size) {
            val id = all[pos].toInt() and 0xFF
            val len = (all[pos + 1].toInt() and 0xFF) or ((all[pos + 2].toInt() and 0xFF) shl 8)
            if (pos + 3 + len > all.size) break
            attrs[id] = String(all, pos + 3, len, Charsets.UTF_8)
            pos += 3 + len
        }
        if (!attrs.containsKey(7)) return
        val uid = readUid(all, 1)
        ancsFetching = false
        applyCall(uid, attrs)
        ancsPump()
    }

    private fun applyCall(uid: Long, attrs: HashMap<Int, String>) {
        if (!shareCalls) return
        val title = attrs[1] ?: ""
        val subtitle = attrs[2] ?: ""
        val message = attrs[3] ?: ""
        callUid = uid
        callName = contactBook.displayName(if (title.isNotBlank()) title else "Unknown caller")
        callDetail = listOf(subtitle, message).filter { it.isNotBlank() }.joinToString(" ")
        callPositive = attrs[6] ?: ""
        callNegative = attrs[7] ?: ""
        callActive = true
        callTick += 1
        changed()
    }

    private fun callAction(action: Int) {
        val gatt = clientGatt ?: return
        val ch = ancsControlChar ?: return
        if (callUid < 0) return
        val uid = callUid
        val cmd = byteArrayOf(2) + uidBytes(uid) + byteArrayOf(action.toByte())
        queueOp { writeChar(gatt, ch, cmd) }
        main.postDelayed({
            if (callActive && callUid == uid) {
                clearCall()
            }
        }, 6000L)
    }

    fun answerCall() {
        callAction(0)
    }

    fun declineCall() {
        callAction(1)
    }

    // ---------- Starting and stopping ----------

    fun start() {
        stop()
        if (!receiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(bondReceiver, filter)
            }
            receiverRegistered = true
        }
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
        running = true
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

    /** Sends a short message to the iPhone app. Music commands go to the iPhone's media service when it is ready. */
    fun sendEvent(text: String) {
        if (amsReady) {
            val cmd = when (text) {
                "MP" -> 2
                "MN" -> 3
                "MV" -> 4
                "VU" -> 5
                "VD" -> 6
                else -> -1
            }
            if (cmd >= 0) {
                amsCommand(cmd)
                return
            }
        }
        val coalesce = text.startsWith("M ") || text.startsWith("Z ")
        enqueueNotify(text.toByteArray(Charsets.UTF_8), coalesce)
    }

    fun stop() {
        stopAmsClient()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(bondReceiver)
            } catch (e: Exception) {
                // Ignore.
            }
            receiverRegistered = false
        }
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
        phoneDevice = null
        notifyQueue.clear()
        notifyBusy = false
        advertising = false
        connectedAddress = null
        dataReady = false
        mode = WatchMode.IDLE
        phoneGallery = false
        phoneMusic = false
        amsStatus = "not started"
        ancsStatus = "not started"
        callActive = false
        callUid = -1L
        clearMusic()
        running = false
    }
}
