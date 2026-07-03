package com.kachat.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Prepares a picked image for KNS profile upload — matches iOS's client-side prep exactly (downscale to a 1400px longest edge, always re-encode as PNG regardless of source format). */
object ImagePrep {
    private const val MAX_DIMENSION = 1400

    fun prepareForUpload(context: Context, uri: Uri): ByteArray {
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Could not read image")

        val scale = MAX_DIMENSION.toFloat() / maxOf(original.width, original.height)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            original
        }

        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
