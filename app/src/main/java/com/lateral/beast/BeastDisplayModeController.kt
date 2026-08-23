package com.lateral.beast

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.Executors

/** Sends Beast-native display timing commands over its USB control channel. */
class BeastDisplayModeController(private val context: Context) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingTiming: Triple<Int, Int, Int>? = null
    private var callback: ((Result<Unit>) -> Unit)? = null
    private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: return finish(Result.failure(IllegalStateException("Beast USB device unavailable")))
            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                finish(Result.failure(SecurityException("USB control permission was denied")))
                return
            }
            pendingTiming?.let { switch(device, it) }
        }
    }

    fun setUltrawide(enabled: Boolean, onComplete: (Result<Boolean>) -> Unit) {
        setNativeTiming(
            width = if (enabled) 3840 else 1920,
            height = 1200,
            refreshRate = 60,
        ) { result -> onComplete(result.map { enabled }) }
    }

    /** Select a native Beast timing that may not be exposed through Android's mode list. */
    fun setNativeTiming(
        width: Int,
        height: Int,
        refreshRate: Int,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val timing = Triple(width, height, refreshRate)
        if (timing !in SUPPORTED_NATIVE_TIMINGS) {
            onComplete(Result.failure(IllegalArgumentException("Unsupported Beast timing ${width}×${height}@${refreshRate}")))
            return
        }
        val device = usb.deviceList.values.firstOrNull(::isBeast)
        if (device == null) {
            onComplete(Result.failure(IllegalStateException("Connect VITURE Beast first")))
            return
        }
        pendingTiming = timing
        callback = onComplete
        if (usb.hasPermission(device)) switch(device, timing) else requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        registerReceiver()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val request = PendingIntent.getBroadcast(
            context, 31, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
        )
        usb.requestPermission(device, request)
    }

    private fun switch(device: UsbDevice, timing: Triple<Int, Int, Int>) {
        executor.execute {
            val connection = usb.openDevice(device)
            val result = if (connection == null) {
                Result.failure(IllegalStateException("Could not open Beast USB control channel"))
            } else {
                val code = try {
                    nativeSetDisplayTiming(
                        device.productId, connection.fileDescriptor,
                        timing.first, timing.second, timing.third,
                    )
                } finally {
                    connection.close()
                }
                if (code == SUCCESS) Result.success(Unit)
                else Result.failure(IllegalStateException("Beast mode command failed ($code)"))
            }
            context.mainExecutor.execute { finish(result) }
        }
    }

    private fun finish(result: Result<Unit>) {
        pendingTiming = null
        callback?.invoke(result)
        callback = null
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun isBeast(device: UsbDevice) =
        device.vendorId == VITURE_VENDOR_ID && device.productId in BEAST_PRODUCT_IDS

    /** USB identity is reliable even when Android labels the HDMI display generically. */
    fun isBeastConnected(): Boolean = usb.deviceList.values.any(::isBeast)

    private external fun nativeSetDisplayTiming(
        productId: Int,
        fileDescriptor: Int,
        width: Int,
        height: Int,
        refreshRate: Int,
    ): Int

    companion object {
        private const val ACTION_USB_PERMISSION = "com.lateral.BEAST_USB_PERMISSION"
        private const val VITURE_VENDOR_ID = 0x35ca
        private val BEAST_PRODUCT_IDS = setOf(0x1201, 0x1211)
        private const val SUCCESS = 0
        private val SUPPORTED_NATIVE_TIMINGS = setOf(
            Triple(1920, 1080, 60), Triple(1920, 1080, 90), Triple(1920, 1080, 120),
            Triple(1920, 1200, 60), Triple(1920, 1200, 90), Triple(1920, 1200, 120),
            Triple(3840, 1200, 60), Triple(3840, 1200, 90), Triple(3840, 1200, 120),
        )

        init { System.loadLibrary("lateral_beast_control") }
    }
}
