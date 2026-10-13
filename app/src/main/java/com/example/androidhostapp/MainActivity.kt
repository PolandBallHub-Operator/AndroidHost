package com.example.androidhostapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var usbDeviceText: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var hostSwitch: Switch
    private var currentAdbDevice: UsbDevice? = null
    private var changingSwitch = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AndroidHostService.ACTION_STATUS) {
                showStatus(
                    intent.getStringExtra("state") ?: "stopped",
                    intent.getStringExtra("message") ?: "停止中",
                    intent.getLongExtra("from_client", 0),
                    intent.getLongExtra("to_client", 0)
                )
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AndroidHostService.ACTION_USB_PERMISSION) return
            val device = usbDeviceExtra(intent)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                currentAdbDevice = device
                startHost(device)
            } else {
                setSwitch(false)
                Toast.makeText(this@MainActivity, "USBデバイスへのアクセスが許可されませんでした", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbDeviceText = findViewById(R.id.usbDeviceText)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        hostSwitch = findViewById(R.id.hostSwitch)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshUsbDevice() }
        findViewById<Button>(R.id.licensesButton).setOnClickListener { LicenseDialog.show(this) }
        hostSwitch.setOnCheckedChangeListener { _, checked ->
            if (changingSwitch) return@setOnCheckedChangeListener
            if (checked) requestUsbAndStart() else stopHost()
        }

        val permissionFilter = IntentFilter(AndroidHostService.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, permissionFilter)
        }
        refreshUsbDevice()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 300)
        }
        val prefs = getSharedPreferences("host_status", MODE_PRIVATE)
        val state = prefs.getString("state", "stopped") ?: "stopped"
        showStatus(state, prefs.getString("message", "停止中") ?: "停止中", 0, 0)
        setSwitch(state == "connected" || state == "connecting" || state == "starting")
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val statusFilter = IntentFilter(AndroidHostService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, statusFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, statusFilter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun requestUsbAndStart() {
        val device = findAdbDevice()
        if (device == null) {
            setSwitch(false)
            Toast.makeText(this, "USB OTGでUSBデバッグ端末を接続してください", Toast.LENGTH_LONG).show()
            return
        }
        currentAdbDevice = device
        if (usbManager.hasPermission(device)) {
            startHost(device)
            return
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this, device.deviceId,
            Intent(AndroidHostService.ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
        statusText.setText(R.string.status_usb_permission)
    }

    private fun findAdbDevice(): UsbDevice? {
        val candidates = usbManager.deviceList.values.filter { findAdbInterface(it) != null }
        currentAdbDevice = candidates.firstOrNull()
        refreshDeviceLabel(candidates.firstOrNull(), candidates.size)
        return candidates.firstOrNull()
    }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
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

    private fun refreshUsbDevice() {
        val device = findAdbDevice()
        if (device == null) refreshDeviceLabel(null, 0)
    }

    private fun refreshDeviceLabel(device: UsbDevice?, count: Int) {
        val displayName = device?.productName ?: device?.deviceName
        usbDeviceText.text = when {
            displayName == null -> getString(R.string.usb_device_not_found)
            count > 1 -> getString(R.string.usb_device_multiple, displayName)
            else -> getString(R.string.usb_device_found, displayName)
        }
    }

    private fun startHost(device: UsbDevice) {
        val intent = Intent(this, AndroidHostService::class.java)
            .setAction(AndroidHostService.ACTION_START)
            .putExtra(AndroidHostService.EXTRA_DEVICE_NAME, device.deviceName)
        startForegroundService(intent)
        statusText.setText(R.string.status_starting)
        setSwitch(true)
    }

    private fun stopHost() {
        val intent = Intent(this, AndroidHostService::class.java).setAction(AndroidHostService.ACTION_STOP)
        startForegroundService(intent)
        statusText.setText(R.string.status_stopping)
    }

    private fun setSwitch(checked: Boolean) {
        changingSwitch = true
        hostSwitch.isChecked = checked
        changingSwitch = false
    }

    private fun showStatus(state: String, message: String, fromClient: Long, toClient: Long) {
        statusText.text = getString(R.string.status_format, message)
        statsText.text = getString(R.string.transfer_stats, formatBytes(fromClient), formatBytes(toClient))
        when (state) {
            "connected", "connecting", "starting" -> setSwitch(true)
            "stopped", "error", "disconnected" -> setSwitch(false)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024.0 && index < units.lastIndex)
        return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }
}
