package com.omni.inbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Notification pictures, downscaled to small JPEGs for the encrypted store. */
object Thumbs {
    private const val MAX = 720

    /** From a MessagingStyle data URI; null when the app didn't grant us read access. */
    fun fromUri(ctx: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?.let(::fromBitmap)
    }.getOrNull()

    fun fromIcon(ctx: Context, icon: Icon): ByteArray? = runCatching {
        (icon.loadDrawable(ctx) as? BitmapDrawable)?.bitmap?.let(::fromBitmap)
    }.getOrNull()

    fun fromBitmap(bmp: Bitmap): ByteArray {
        val scale = MAX.toFloat() / maxOf(bmp.width, bmp.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}
