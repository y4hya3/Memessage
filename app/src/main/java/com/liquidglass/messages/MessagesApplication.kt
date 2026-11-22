package com.liquidglass.messages

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.liquidglass.messages.di.ServiceLocator
import com.liquidglass.messages.util.DevicePerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App entry point. Builds the [ServiceLocator], tunes Coil's image caches
 * for the device's memory tier, and registers notification channels once at
 * process start.
 *
 * Access the container anywhere with:
 *   `(context.applicationContext as MessagesApplication).container`
 */
class MessagesApplication : Application(), ImageLoaderFactory {

    lateinit var container: ServiceLocator
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.liquidglass.messages.data.mms.MmsLog.init(this)
        container = ServiceLocator(this)
        appScope.launch {
            runCatching { container.notificationHelper.createChannels() }
            // Alarms can be lost (force-stop, some OEM task killers); re-arm Send Later.
            runCatching { container.scheduledMessages.rescheduleAll() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val lowRam = DevicePerformance.isLowRam(this)
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) 0.12 else 0.22)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (lowRam) 32L * 1024L * 1024L else 64L * 1024L * 1024L)
                    .build()
            }
            .allowRgb565(lowRam)
            .crossfade(if (lowRam) 100 else 180)
            .respectCacheHeaders(false)
            .build()
    }
}

/** Convenience accessor for the DI container from any [android.content.Context]. */
val android.content.Context.appContainer: ServiceLocator
    get() = (applicationContext as MessagesApplication).container
