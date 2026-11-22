# Default Android ProGuard rules apply via proguard-android-optimize.txt.

# Keep Compose runtime metadata.
-keepclassmembers class androidx.compose.** { *; }

# Telephony / SMS framework classes referenced by reflection in some OEM ROMs.
-keep class android.provider.Telephony** { *; }

# Keep our manifest-registered components (application, activities, receivers, services).
-keep class com.liquidglass.messages.MessagesApplication { *; }
-keep class com.liquidglass.messages.MainActivity { *; }
-keep class com.liquidglass.messages.data.receiver.** { *; }
-keep class com.liquidglass.messages.data.service.** { *; }
-keep class com.liquidglass.messages.data.mms.MmsDownloadedReceiver { *; }
-keep class com.liquidglass.messages.data.mms.MmsSentReceiver { *; }
-keep class com.liquidglass.messages.data.schedule.ScheduledSendReceiver { *; }
-keep class com.liquidglass.messages.data.schedule.RescheduleReceiver { *; }
-keep class com.liquidglass.messages.notification.NotificationActionReceiver { *; }

# Keep Coil runtime components used on all devices.
-dontwarn coil.**
-keepclassmembers class * implements coil.ImageLoaderFactory { *; }

# Kotlin coroutines.
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
