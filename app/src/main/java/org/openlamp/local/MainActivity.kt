package org.openlamp.local

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.security.SecureRandom
import java.util.Locale

/** Sends lamp commands through BLE advertising while the activity is in the foreground. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private lateinit var status: TextView
    private lateinit var mode: Spinner
    private lateinit var controls: List<Button>
    private lateinit var prefs: SharedPreferences
    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeCallback: AdvertiseCallback? = null
    private var controllerId = 0
    private var generation = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the old preference file and keys so an update keeps the lamp pairing.
        prefs = getSharedPreferences("controller", MODE_PRIVATE)
        if (!prefs.contains("id")) {
            var id: Int
            do { id = random.nextInt(0x1000000) and 0xFFFFF0FF.toInt() } while (id == 0)
            prefs.edit().putInt("id", id).apply()
        }
        controllerId = prefs.getInt("id", 0)

        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        mode = findViewById(R.id.mode)
        controls = listOf(findViewById(R.id.turn_on), findViewById(R.id.turn_off), findViewById(R.id.pair))
        controls[0].setOnClickListener { send(0x10, getString(R.string.turn_on)) }
        controls[1].setOnClickListener { send(0x11, getString(R.string.turn_off)) }
        controls[2].setOnClickListener { send(0x28, getString(R.string.pair_action)) }

        mode.adapter = ArrayAdapter.createFromResource(
            this, R.array.compatibility_modes, android.R.layout.simple_spinner_item,
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // Earlier versions did not include the variant sent by the green Zhi Deng Pro app.
        // Select it once on upgrade; preserve the controller ID and subsequent mode choices.
        if (prefs.getInt("protocol_revision", 0) < 1) {
            prefs.edit().putInt("mode", 4).putInt("protocol_revision", 1).apply()
        }
        mode.setSelection(prefs.getInt("mode", 4).coerceIn(0, 4))
        applySystemBarInsets()
        requestBluetoothPermissions()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets() {
        val page = findViewById<ScrollView>(R.id.page)
        page.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        page.requestApplyInsets()
    }

    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < 31 ||
        (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermissions()) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT), 1,
            )
        }
    }

    // The permissions are checked above on every send; no scanning or location access is used.
    @SuppressLint("MissingPermission")
    private fun send(command: Int, action: String) {
        if (busy) return
        if (!hasBluetoothPermissions()) {
            status.setText(R.string.permission_required)
            requestBluetoothPermissions()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.setText(R.string.bluetooth_disabled)
            return
        }
        val availableAdvertiser = adapter.bluetoothLeAdvertiser
        if (availableAdvertiser == null) {
            status.setText(R.string.advertising_unavailable)
            return
        }
        advertiser = availableAdvertiser

        val chosen = mode.selectedItemPosition
        val sequence = (prefs.getInt("sequence", 0) + 1) and 255
        prefs.edit().putInt("sequence", sequence).putInt("mode", chosen).apply()
        val packets = mutableListOf<AdvertiseData>()
        try {
            val variants = if (chosen == 0) listOf(4, 2, 3, 1) else listOf(chosen)
            for (variant in variants) {
                var data: AdvertiseData? = null
                // Android deduplicates UUIDs. Retry a seed if two encoded words collide.
                for (attempt in 0 until 64) {
                    val payload = LampCodec.encode(variant, command, controllerId, sequence, 1 + random.nextInt(0xFFF5))
                    data = toAdvertisingData(payload)
                    if (data != null) break
                }
                packets.add(checkNotNull(data) { "无法生成广播数据" })
            }
        } catch (error: Exception) {
            status.text = getString(R.string.encoding_failed, error.message)
            return
        }

        busy = true
        setControls(false)
        val token = ++generation
        status.text = getString(R.string.sending, action)
        val pairing = command == 0x28
        // Repeat the same command, sequence and seed as the vendor app does.
        // Give pairing four seconds of broadcasts; shorter power commands still retry.
        val repeats = if (pairing) 8 / packets.size else maxOf(1, 4 / packets.size)
        startPacket(List(repeats) { packets }.flatten(), 0, action, pairing, token)
    }

    private fun toAdvertisingData(payload: ByteArray): AdvertiseData? {
        val builder = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        val seen = mutableSetOf<Int>()
        for (i in payload.indices step 2) {
            val word = (payload[i].toInt() and 255) or ((payload[i + 1].toInt() and 255) shl 8)
            if (!seen.add(word)) return null
            builder.addServiceUuid(ParcelUuid.fromString(String.format(Locale.ROOT, "%08x-0000-1000-8000-00805f9b34fb", word)))
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun startPacket(packets: List<AdvertiseData>, index: Int, action: String, pairing: Boolean, token: Int) {
        if (token != generation) return
        if (index == packets.size) {
            busy = false
            setControls(true)
            status.text = if (pairing) getString(R.string.sent_pairing) else getString(R.string.sent, action)
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // Match the vendor's legacy ADV_IND packets, including the Flags AD field.
            .setConnectable(true)
            .setTimeout(2000)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (token != generation) return
                Log.i("OpenLamp", "Advertising started: $action, packet $index")
                handler.postDelayed({
                    if (token != generation) return@postDelayed
                    stopAdvertisement()
                    handler.postDelayed({ startPacket(packets, index + 1, action, pairing, token) }, 20)
                }, 500)
            }

            override fun onStartFailure(errorCode: Int) {
                if (token != generation) return
                cancelSending()
                status.text = getString(R.string.advertising_failed, errorCode)
                Log.e("OpenLamp", "Advertising failed: $errorCode")
            }
        }
        activeCallback = callback
        try {
            checkNotNull(advertiser).startAdvertising(settings, packets[index], callback)
        } catch (error: RuntimeException) {
            cancelSending()
            status.text = getString(R.string.send_failed, error.message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisement() {
        activeCallback?.let { callback ->
            try { advertiser?.stopAdvertising(callback) } catch (_: RuntimeException) { }
        }
        activeCallback = null
    }

    private fun cancelSending() {
        ++generation
        handler.removeCallbacksAndMessages(null)
        stopAdvertisement()
        busy = false
        setControls(true)
    }

    override fun onPause() {
        super.onPause()
        if (busy) {
            cancelSending()
            status.setText(R.string.send_paused)
        }
    }

    private fun setControls(enabled: Boolean) {
        controls.forEach { it.isEnabled = enabled }
        mode.isEnabled = enabled
    }
}
