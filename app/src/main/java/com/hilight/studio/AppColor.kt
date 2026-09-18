package com.hilight.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local icon sampling only. Missing or monochrome icons keep the rule's manual color. */
object AppColor {
    private data class Entry(val version: Long, val updated: Long, val color: Int?)
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > 64
    }

    /** Package queries, drawing and sampling all run off the UI thread; concurrent requests coalesce. */
    suspend fun colorFor(context: Context, packageName: String): Int? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pm = context.applicationContext.packageManager
            try {
                val info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                val old = cache[packageName]
                if (old != null && old.version == info.longVersionCode && old.updated == info.lastUpdateTime) {
                    return@withLock old.color
                }
                val icon = pm.getApplicationIcon(packageName).mutate()
                val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                val color = try {
                    icon.setBounds(0, 0, bitmap.width, bitmap.height)
                    icon.draw(Canvas(bitmap))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    dominantIconColor(pixels)
                } finally {
                    bitmap.recycle()
                }
                cache[packageName] = Entry(info.longVersionCode, info.lastUpdateTime, color)
                color
            } catch (_: PackageManager.NameNotFoundException) {
                cache.remove(packageName)
                null
            } catch (_: RuntimeException) {
                // An unavailable/broken drawable must never prevent an alert or editing a rule.
                cache.remove(packageName)
                null
            }
        }
    }
}

/** Quantized color voting avoids averaging different brand colors into a muddy new color. */
internal fun dominantIconColor(pixels: IntArray): Int? {
    val counts = IntArray(4096)
    val red = IntArray(4096)
    val green = IntArray(4096)
    val blue = IntArray(4096)
    for (pixel in pixels) {
        if ((pixel ushr 24) < 192) continue
        val r = (pixel ushr 16) and 255
        val g = (pixel ushr 8) and 255
        val b = pixel and 255
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        // Ignore transparent edges, white backgrounds and neutral/very dark artwork.
        if (high < 48 || high - low < 35) continue
        val bucket = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
        counts[bucket]++
        red[bucket] += r
        green[bucket] += g
        blue[bucket] += b
    }
    val best = counts.indices.maxByOrNull { counts[it] } ?: return null
    val count = counts[best]
    // Do not pick one incidental colored pixel from otherwise monochrome artwork.
    if (count < maxOf(1, pixels.size / 100)) return null
    return (0xFF shl 24) or ((red[best] / count) shl 16) or
        ((green[best] / count) shl 8) or (blue[best] / count)
}
