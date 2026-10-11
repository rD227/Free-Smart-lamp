package org.openlamp.local

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.security.SecureRandom
import java.util.Locale

data class LampState(
    val mode: Int = 4,
    val warmth: Int = 50,
    val brightness: Int = 100,
    val lightingApplyMode: LightingApplyMode = LightingApplyMode.MANUAL,
    val timerMinutes: Int = 30,
    val timerDeadline: Long = 0,
    val status: String = "",
    val busy: Boolean = false,
    val sendingLighting: Boolean = false,
    val transmissionId: Int = 0,
    val sendDurationMillis: Long = 0,
)

/** Owns preferences and Bluetooth. It has no dependency on any UI widget or Activity. */
class LampController(private val context: Context, private val prefs: SharedPreferences) {
    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeCallback: AdvertiseCallback? = null
    private var generation = 0
    private val lightingUpdates = LightingUpdates()
    private val controllerId: Int
    var onStateChanged: (LampState) -> Unit = {}
    var state: LampState
        private set

    init {
        if (!prefs.contains("id")) {
            var id: Int
            do { id = random.nextInt(0x1000000) and 0xFFFFF0FF.toInt() } while (id == 0)
            prefs.edit().putInt("id", id).apply()
        }
        controllerId = prefs.getInt("id", 0)
        if (prefs.getInt("protocol_revision", 0) < 1) {
            prefs.edit().putInt("mode", 4).putInt("protocol_revision", 1).apply()
        }
        state = LampState(
            mode = prefs.getInt("mode", 4).coerceIn(0, 4),
            warmth = prefs.getInt("warmth", 50).coerceIn(0, 100),
            brightness = prefs.getInt("brightness", 100).coerceIn(1, 100),
            lightingApplyMode = LightingApplyMode.entries.firstOrNull { it.name == prefs.getString("lighting_apply_mode", null) } ?: LightingApplyMode.MANUAL,
            timerMinutes = prefs.getInt("timer_minutes", 30).coerceIn(1, 720),
            timerDeadline = prefs.getLong("timer_deadline", 0),
            status = context.getString(R.string.ready),
        )
    }

    fun selectMode(mode: Int) {
        if (state.busy) return
        require(mode in 0..4)
        prefs.edit().putInt("mode", mode).apply()
        update(state.copy(mode = mode))
    }

    fun selectLightingApplyMode(mode: LightingApplyMode) {
        if (state.busy && !state.sendingLighting) return
        lightingUpdates.discardPending()
        prefs.edit().putString("lighting_apply_mode", mode.name).apply()
        update(state.copy(lightingApplyMode = mode))
    }

    fun editLighting(warmth: Int, brightness: Int, gestureEnded: Boolean) {
        if (state.busy && !state.sendingLighting) return
        val values = LightingValues(warmth, brightness)
        saveLighting(values)
        when (state.lightingApplyMode) {
            LightingApplyMode.MANUAL -> Unit
            LightingApplyMode.ON_RELEASE -> if (gestureEnded) queueLighting(values, preview = false)
            LightingApplyMode.WHILE_SLIDING -> queueLighting(values, preview = !gestureEnded)
        }
    }

    fun applyLighting(warmth: Int, brightness: Int) {
        if (state.busy && !state.sendingLighting) return
        val values = LightingValues(warmth, brightness)
        saveLighting(values)
        queueLighting(values, preview = false)
    }

    private fun saveLighting(values: LightingValues) {
        val (warmth, brightness) = values
        if (state.warmth == warmth && state.brightness == brightness) return
        prefs.edit().putInt("warmth", warmth).putInt("brightness", brightness).apply()
        update(state.copy(warmth = warmth, brightness = brightness))
    }

    private fun queueLighting(values: LightingValues, preview: Boolean) {
        lightingUpdates.offer(LightingUpdate(values, preview))
        if (!state.busy) sendNextLighting()
    }

    private fun sendNextLighting() {
        val next = lightingUpdates.take() ?: return
        if (!sendNow(LampCommand.temperature(next.values.warmth, next.values.brightness), context.getString(R.string.lighting_action), next.preview, lighting = true)) {
            lightingUpdates.clear()
            update(state.copy(busy = false, sendingLighting = false))
        }
    }

    fun selectTimer(minutes: Int) {
        if (state.busy) return
        require(minutes in 1..720)
        prefs.edit().putInt("timer_minutes", minutes).apply()
        update(state.copy(timerMinutes = minutes))
    }

    fun hasBluetoothPermissions() = Build.VERSION.SDK_INT < 31 ||
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    fun send(command: LampCommand, action: String) {
        if (state.busy) return
        lightingUpdates.clear()
        sendNow(command, action)
    }

    @SuppressLint("MissingPermission")
    private fun sendNow(command: LampCommand, action: String, preview: Boolean = false, lighting: Boolean = false): Boolean {
        if (!hasBluetoothPermissions()) {
            update(state.copy(status = context.getString(R.string.permission_required)))
            return false
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update(state.copy(status = context.getString(R.string.bluetooth_disabled)))
            return false
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            update(state.copy(status = context.getString(R.string.advertising_unavailable)))
            return false
        }
        val sequence = (prefs.getInt("sequence", 0) + 1) and 255
        prefs.edit().putInt("sequence", sequence).apply()
        val packets = mutableListOf<AdvertiseData>()
        try {
            val variants = if (state.mode == 0) listOf(4, 2, 3, 1) else listOf(state.mode)
            for (variant in variants) {
                var data: AdvertiseData? = null
                repeat(64) {
                    if (data == null) {
                        data = advertisingData(LampCodec.encode(variant, command, controllerId, sequence, 1 + random.nextInt(0xFFF5)))
                    }
                }
                packets.add(checkNotNull(data) { context.getString(R.string.packet_failed) })
            }
        } catch (error: Exception) {
            update(state.copy(status = context.getString(R.string.encoding_failed, error.message)))
            return false
        }
        val repeats = if (preview) 1 else if (command.code == 0x28) 8 / packets.size else maxOf(1, 4 / packets.size)
        val transmission = List(repeats) { packets }.flatten()
        val holdMillis = if (preview) PREVIEW_HOLD_MILLIS else RELIABLE_HOLD_MILLIS
        val token = ++generation
        update(state.copy(busy = true, sendingLighting = lighting, transmissionId = token, status = context.getString(R.string.sending, action), sendDurationMillis = transmission.size * (holdMillis + PACKET_GAP_MILLIS)))
        startPacket(transmission, 0, command, action, token, holdMillis)
        return true
    }

    private fun advertisingData(payload: ByteArray): AdvertiseData? {
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
    private fun startPacket(packets: List<AdvertiseData>, index: Int, command: LampCommand, action: String, token: Int, holdMillis: Long) {
        if (token != generation) return
        // A newer slider position can replace remaining retries of an older position.
        // In automatic compatibility mode, finish one pass through all four variants.
        val superseded = state.sendingLighting && lightingUpdates.hasPending && index > 0 && (state.mode != 0 || index == packets.size)
        if (index == packets.size || superseded) {
            if (state.sendingLighting) {
                lightingUpdates.finish()
                if (lightingUpdates.hasPending) { sendNextLighting(); return }
            }
            var completed = state.copy(busy = false, sendingLighting = false, status = context.getString(if (command.code == 0x28) R.string.sent_pairing else R.string.sent, action))
            if (command.code == 0x41) {
                val minutes = command.arg0 or (command.arg1 shl 8)
                val deadline = if (minutes == 0) 0 else System.currentTimeMillis() + minutes * 60_000L
                prefs.edit().putLong("timer_deadline", deadline).apply()
                completed = completed.copy(timerDeadline = deadline)
            }
            update(completed)
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(2000)
            .build()
        val watchdog = Runnable { if (token == generation) fail(context.getString(R.string.advertising_timeout)) }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                handler.removeCallbacks(watchdog)
                if (token != generation) {
                    try { advertiser?.stopAdvertising(this) } catch (_: RuntimeException) { }
                    return
                }
                Log.i("OpenLamp", "Advertising started: $action, packet $index")
                handler.postDelayed({
                    if (token != generation) return@postDelayed
                    stopAdvertisement()
                    handler.postDelayed({ startPacket(packets, index + 1, command, action, token, holdMillis) }, PACKET_GAP_MILLIS)
                }, holdMillis)
            }

            override fun onStartFailure(errorCode: Int) {
                if (token != generation) return
                fail(context.getString(R.string.advertising_failed, errorCode))
                Log.e("OpenLamp", "Advertising failed: $errorCode")
            }
        }
        activeCallback = callback
        handler.postDelayed(watchdog, 2500)
        try {
            checkNotNull(advertiser).startAdvertising(settings, packets[index], callback)
        } catch (error: RuntimeException) {
            fail(context.getString(R.string.send_failed, error.message))
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisement() {
        activeCallback?.let { try { advertiser?.stopAdvertising(it) } catch (_: RuntimeException) { } }
        activeCallback = null
    }

    private fun fail(message: String) {
        cancel()
        update(state.copy(status = message))
    }

    fun cancel() {
        ++generation
        lightingUpdates.clear()
        handler.removeCallbacksAndMessages(null)
        stopAdvertisement()
        if (state.busy) update(state.copy(busy = false, sendingLighting = false, status = context.getString(R.string.send_paused)))
    }

    private fun update(next: LampState) {
        state = next
        onStateChanged(next)
    }

    private companion object {
        const val PREVIEW_HOLD_MILLIS = 160L
        const val RELIABLE_HOLD_MILLIS = 500L
        const val PACKET_GAP_MILLIS = 20L
    }
}
