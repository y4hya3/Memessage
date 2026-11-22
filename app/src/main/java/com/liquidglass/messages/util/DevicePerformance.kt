package com.liquidglass.messages.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Hardware-tier detection used to keep scrolling and animations smooth across
 * entry-level 2–3 GB devices (Redmi 9C), mid-range tablets (Galaxy Tab A7 LTE),
 * and high-refresh-rate flagships (Xiaomi 15).
 */
object DevicePerformance {

    private const val LOW_RAM_THRESHOLD_BYTES = 3_600L * 1024L * 1024L

    @Volatile
    private var cachedLowRam: Boolean? = null

    /**
     * True on memory-constrained devices (e.g. Android Go / 2–3 GB RAM devices
     * such as Redmi 9C and Galaxy Tab A7 LTE).
     */
    fun isLowRam(context: Context): Boolean {
        cachedLowRam?.let { return it }
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = if (am == null) {
            false
        } else if (am.isLowRamDevice) {
            true
        } else {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem in 1L..LOW_RAM_THRESHOLD_BYTES
        }
        cachedLowRam = lowRam
        return lowRam
    }

    /**
     * Real-time RenderEffect glass refraction requires Android 12+ (API 31) and
     * is kept lightweight on low-RAM hardware.
     */
    fun supportsBackdropGlass(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Full-screen Gaussian blur sweeps behind modal sheets/menus are heavy on
     * entry-level GPUs and low-RAM tablets; restrict them to API 31+ devices
     * with ample memory headroom.
     */
    fun supportsFullScreenBlur(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isLowRam(context)

    /**
     * AGSL lens shader refraction runs at full fidelity on Android 13+ flagship
     * GPUs while skipping multi-tap chromatic sampling on low-RAM devices.
     */
    fun supportsLensShader(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isLowRam(context)
}
