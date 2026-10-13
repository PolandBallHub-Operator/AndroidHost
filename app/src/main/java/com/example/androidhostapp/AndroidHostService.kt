package com.example.androidhostapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.AdbStream
import com.cgutman.adblib.UsbChannel
import com.genymobile.gnirehtet.relay.Relay
import com.genymobile.gnirehtet.relay.RelayTrafficStats
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Host role: Android runs the gnirehtet relay and controls a separate client phone over USB ADB. */
class AndroidHostService : Service() {
    companion object {
        const val ACTION_START = "com.example.androidhostapp.action.START"
        const val ACTION_STOP = "com.example.androidhostapp.action.STOP"
        const val ACTION_STATUS = "com.example.androidhostapp.action.STATUS"
        const val ACTION_USB_PERMISSION = "com.example.androidhostapp.action.USB_PERMISSION"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val CHANNEL_ID = "android_host_relay"
        private const val NOTIFICATION_ID = 31416
        private const val PREFS = "host_status"
        private const val PREF_STATE = "state"
        private const val PREF_MESSAGE = "message"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopRequested = AtomicBoolean(false)
    private val startInProgress = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong(0)
    private var worker: Thread? = null
    private var relay: Relay? = null
    private var relayThread: Thread? = null
    private var adbConnection: AdbConnection? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var attachedDeviceName: String? = null
    @Volatile private var hostActive = false

    private val statsTicker = object : Runnable {
        override fun run() {
            if (!hostActive) return
            val up = RelayTrafficStats.getFromClient()
            val down = RelayTrafficStats.getToClient()
            publish("connected", "ADB接続済み・リレー稼働中", up, down)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                val detached = usbDeviceExtra(intent)
                if (detached?.deviceName == attachedDeviceName) {
                    publish("disconnected", "USB端末が切断されました", 0, 0)
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbDetachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbDetachReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("AndroidHostService", "onStartCommand action=${intent?.action} startId=$startId generation=${lifecycleGeneration.get()}")
        when (intent?.action) {
            ACTION_STOP -> {
                startInForeground()
                val generation = lifecycleGeneration.incrementAndGet()
                Log.w("AndroidHostService", "ACTION_STOP received generation=$generation")
                stopRequested.set(true)
                startInProgress.set(false)
                worker?.interrupt()
                stopHostAsync(generation)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startInForeground()
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
                if (deviceName.isNullOrBlank()) {
                    fail("USB端末が指定されていません")
                } else {
                    Log.i("AndroidHostService", "ACTION_START received device=$deviceName")
                    startWorker(deviceName)
                }
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = makeNotification("USB端末とリレーを起動しています")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startWorker(deviceName: String) {
        if (worker?.isAlive == true || hostActive ||
            !startInProgress.compareAndSet(false, true)) {
            Log.i("AndroidHostService", "Ignoring duplicate Host start request")
            return
        }
        val generation = lifecycleGeneration.incrementAndGet()
        stopRequested.set(false)
        attachedDeviceName = deviceName
        worker = Thread({
            try {
                startHost(deviceName, generation)
            } catch (e: Exception) {
                Log.e("AndroidHostService", "Host setup failed", e)
                if (!stopRequested.get() && lifecycleGeneration.get() == generation) {
                    bestEffortStopRemote()
                    fail(e.message ?: e.javaClass.simpleName)
                }
                if (lifecycleGeneration.get() == generation) stopSelf()
            }
        }, "AndroidHost-setup").apply { start() }
    }

    private fun startHost(deviceName: String, generation: Long) {
        val networkStatus = getHostNetworkStatus()
        Log.i("AndroidHostService", networkStatus)
        publish("starting", "$networkStatus / リレーを起動しています", 0, 0)
        RelayTrafficStats.reset()

        val relayStarted = CountDownLatch(1)
        val relayFailure = AtomicReference<Throwable?>(null)
        val newRelay = Relay(HostConfig.RELAY_PORT) { relayStarted.countDown() }
        relay = newRelay
        relayThread = Thread({
            try {
                Log.i("AndroidHostService", "Starting local relay on 127.0.0.1:${HostConfig.RELAY_PORT}")
                newRelay.run()
            } catch (t: Throwable) {
                relayFailure.set(t)
                relayStarted.countDown()
                Log.e("AndroidHostService", "Local relay terminated", t)
                if (!stopRequested.get() && lifecycleGeneration.get() == generation) {
                    fail("リレー起動失敗: ${t.message ?: t.javaClass.simpleName}")
                    Thread({
                        bestEffortStopRemote()
                        if (lifecycleGeneration.get() == generation) stopSelf()
                    }, "AndroidHost-relay-failure-cleanup").start()
                }
            }
        }, "Gnirehtet-relay").apply { start() }

        if (!relayStarted.await(5, TimeUnit.SECONDS)) {
            throw IOException("リレー待受の開始がタイムアウトしました")
        }
        Log.i("AndroidHostService", "Relay startup latch released")
        relayFailure.get()?.let { throw IOException("リレー待受に失敗しました", it) }
        Log.i("AndroidHostService", "Relay startup failure check passed")
        verifyLocalRelay(newRelay)
        Log.i("AndroidHostService", "Relay self-test complete")
        checkNotStopped()

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        Log.i("AndroidHostService", "Opening USB ADB device")
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: throw IOException("ADB USB端末が見つかりません。ケーブルを確認してください")
        if (!usbManager.hasPermission(device)) {
            throw IOException("USBアクセス許可がありません。画面から再接続してください")
        }
        val adbInterface = findAdbInterface(device)
            ?: throw IOException("USB端末にADBインターフェースがありません。相手機でUSBデバッグを有効にしてください")
        val connection = usbManager.openDevice(device)
            ?: throw IOException("USB端末を開けませんでした")
        usbConnection = connection
        if (!connection.claimInterface(adbInterface, true)) {
            throw IOException("ADB USBインターフェースを取得できません")
        }

        publish("connecting", "ADB認証を待っています。相手機のRSA許可ダイアログを確認してください", 0, 0)
        val crypto = loadOrCreateAdbCrypto()
        val adb = AdbConnection.create(UsbChannel(connection, adbInterface), crypto)
        adb.setIncomingStreamHandler { stream, destination ->
            handleReverseStream(stream, destination)
        }
        adbConnection = adb
        Log.i("AndroidHostService", "Starting ADB protocol connection")
        adb.connect()
        Log.i("AndroidHostService", "ADB protocol connection established")
        checkNotStopped()

        // Equivalent to: adb reverse localabstract:gnirehtet tcp:31416
        val reverseReply = runAdbService(adb, HostConfig.ADB_REVERSE_FORWARD)
        if (reverseReply.contains("FAIL", ignoreCase = true)) {
            throw IOException("adb reverseを設定できませんでした: ${reverseReply.trim()}")
        }
        if (!reverseReply.contains("OKAY", ignoreCase = true)) {
            throw IOException("adb reverseの応答を確認できませんでした: ${reverseReply.trim()}")
        }
        checkNotStopped()

        // The separate gnirehtet client APK must already be installed on the connected phone.
        val startReply = runAdbService(adb, HostConfig.CLIENT_START_SHELL)
        if (startReply.contains("Error:", ignoreCase = true) ||
            startReply.contains("does not exist", ignoreCase = true) ||
            startReply.contains("not found", ignoreCase = true)) {
            throw IOException("相手機にgnirehtetクライアントAPKがないか、起動できません: ${startReply.trim()}")
        }

        hostActive = true
        publish("connected", "ADB接続済み・リレー稼働中", 0, 0)
        mainHandler.removeCallbacks(statsTicker)
        mainHandler.post(statsTicker)
    }

    /** Confirm that the relay is still listening after its startup latch fires. */
    private fun verifyLocalRelay(relayInstance: Relay) {
        var lastError: IOException? = null
        repeat(10) { attempt ->
            try {
                Log.i("AndroidHostService", "Relay self-test attempt ${attempt + 1}/10")
                Socket("127.0.0.1", HostConfig.RELAY_PORT).use {
                    Log.i("AndroidHostService", "Local relay self-test succeeded on tcp:${HostConfig.RELAY_PORT}")
                }
                return
            } catch (e: IOException) {
                lastError = e
                Log.w("AndroidHostService", "Relay self-test failed: ${e.message}")
                Thread.sleep(100)
            }
        }
        relayInstance.stop()
        throw IOException(
            "Host内relay tcp:${HostConfig.RELAY_PORT}へ接続できません: ${lastError?.message}",
            lastError
        )
    }

    private fun checkNotStopped() {
        if (stopRequested.get()) throw IOException("停止要求を受け付けました")
    }

    /** Handles the A_OPEN sent by adbd when the reverse local socket is opened on the client. */
    private fun handleReverseStream(stream: AdbStream, destination: String) {
        Log.i("AndroidHostService", "ADB reverse stream received: $destination")
        if (!destination.startsWith("tcp:") ||
            destination.substringAfter("tcp:").toIntOrNull() != HostConfig.RELAY_PORT) {
            Log.e("AndroidHostService", "Unsupported reverse destination: $destination")
            try { stream.close() } catch (_: Exception) { }
            return
        }
        val socket = try {
            Socket("127.0.0.1", HostConfig.RELAY_PORT).apply { tcpNoDelay = true }
        } catch (_: IOException) {
            Log.e("AndroidHostService", "Cannot connect reverse stream to local relay")
            try { stream.close() } catch (_: Exception) { }
            return
        }
        Log.i("AndroidHostService", "Reverse stream connected to local relay:${HostConfig.RELAY_PORT}")
        val closed = AtomicBoolean(false)
        fun closeBoth() {
            if (closed.compareAndSet(false, true)) {
                try { socket.close() } catch (_: Exception) { }
                try { stream.close() } catch (_: Exception) { }
            }
        }
        Thread({
            try {
                val output = socket.getOutputStream()
                while (!closed.get()) output.write(stream.read())
            } catch (_: Exception) {
                Log.i("AndroidHostService", "ADB-to-relay reverse stream closed")
                closeBoth()
            }
        }, "Gnirehtet-reverse-adb-to-relay").start()
        Thread({
            try {
                val input = socket.getInputStream()
                val buffer = ByteArray(4096)
                while (!closed.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) stream.write(buffer.copyOf(count))
                }
            } catch (_: Exception) {
                // Closing either side is the normal way to terminate the bridge.
            } finally {
                Log.i("AndroidHostService", "Relay-to-ADB reverse stream closed")
                closeBoth()
            }
        }, "Gnirehtet-reverse-relay-to-adb").start()
    }

    private fun getHostNetworkStatus(): String {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return "Hostネットワークなし"
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return "Hostネットワーク情報なし"
        val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "モバイル"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "その他"
        }
        return "Hostネットワーク=$transport INTERNET=$internet VALIDATED=$validated"
    }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            // Standard Android ADB USB interface descriptor: class 0xff, subclass 0x42, protocol 0x01.
            if (usbInterface.interfaceClass == 0xff &&
                usbInterface.interfaceSubclass == 0x42 &&
                usbInterface.interfaceProtocol == 0x01) return usbInterface
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
        }

    private fun loadOrCreateAdbCrypto(): AdbCrypto {
        val privateKey = File(filesDir, "adb_private_key")
        val publicKey = File(filesDir, "adb_public_key")
        val base64 = object : AdbBase64 {
            override fun encodeToString(data: ByteArray): String =
                Base64.encodeToString(data, Base64.NO_WRAP)
        }
        try {
            return AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey)
        } catch (_: Exception) {
            val crypto = AdbCrypto.generateAdbKeyPair(base64)
            crypto.saveAdbKeyPair(privateKey, publicKey)
            return crypto
        }
    }

    /** Opens one ADB service and drains its response until adbd closes the stream. */
    private fun runAdbService(adb: AdbConnection, service: String, allowDuringStop: Boolean = false): String {
        val stream: AdbStream = adb.open(service)
        val output = ByteArrayOutputStream()
        try {
            while (allowDuringStop || !stopRequested.get()) {
                output.write(stream.read())
            }
        } catch (_: IOException) {
            // ADB closes one-shot reverse/shell streams when their response is complete.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("ADB操作が中断されました")
        } finally {
            try { stream.close() } catch (_: Exception) { }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun publish(state: String, message: String, fromClient: Long, toClient: Long) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_STATE, state)
            .putString(PREF_MESSAGE, message)
            .apply()
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra("state", state)
            .putExtra("message", message)
            .putExtra("from_client", fromClient)
            .putExtra("to_client", toClient)
        sendBroadcast(intent)
        if (state != "stopped") {
            val notification = makeNotification(message)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun fail(message: String) {
        hostActive = false
        mainHandler.removeCallbacks(statsTicker)
        publish("error", message, 0, 0)
    }

    private fun makeNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidHostApp")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "gnirehtet Hostリレー", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        hostActive = false
        stopRequested.set(true)
        mainHandler.removeCallbacks(statsTicker)
        cleanup()
        try { unregisterReceiver(usbDetachReceiver) } catch (_: Exception) { }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val previousState = prefs.getString(PREF_STATE, "stopped")
        val finalState = if (previousState == "error") "error" else "stopped"
        val finalMessage = if (finalState == "error") prefs.getString(PREF_MESSAGE, "エラー") ?: "エラー" else "停止中"
        prefs.edit().putString(PREF_STATE, finalState).putString(PREF_MESSAGE, finalMessage).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra("state", finalState).putExtra("message", finalMessage)
            .putExtra("from_client", 0L).putExtra("to_client", 0L))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun cleanup() {
        val adb = adbConnection
        if (adb != null) {
            try { adb.close() } catch (_: Exception) { }
        }
        adbConnection = null
        relay?.stop()
        relay = null
        try { relayThread?.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        relayThread = null
        try { usbConnection?.close() } catch (_: Exception) { }
        usbConnection = null
        attachedDeviceName = null
    }

    /** Keep blocking ADB I/O off the main thread while removing the reverse and stopping the client. */
    private fun stopHostAsync(stopGeneration: Long) {
        Thread({
            Log.i("AndroidHostService", "Stopping Host resources generation=$stopGeneration")
            val adbToStop = adbConnection
            val relayToStop = relay
            val relayThreadToJoin = relayThread
            val usbToClose = usbConnection
            bestEffortStopRemote(adbToStop)
            hostActive = false
            mainHandler.removeCallbacks(statsTicker)
            relayToStop?.stop()
            try { relayThreadToJoin?.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            // Do not clear or close resources belonging to a newer start request.
            if (adbConnection === adbToStop) adbConnection = null
            if (relay === relayToStop) {
                relay = null
                relayThread = null
            }
            if (usbConnection === usbToClose) {
                try { usbToClose?.close() } catch (_: Exception) { }
                usbConnection = null
            }
            if (lifecycleGeneration.get() == stopGeneration) stopSelf()
        }, "AndroidHost-stop").start()
    }

    private fun bestEffortStopRemote(adb: AdbConnection? = adbConnection) {
        adb ?: return
        try { runAdbService(adb, HostConfig.CLIENT_STOP_SHELL, allowDuringStop = true) } catch (_: Exception) { }
        try { runAdbService(adb, HostConfig.ADB_REVERSE_REMOVE, allowDuringStop = true) } catch (_: Exception) { }
        try { adb.close() } catch (_: Exception) { }
        if (adbConnection === adb) adbConnection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
