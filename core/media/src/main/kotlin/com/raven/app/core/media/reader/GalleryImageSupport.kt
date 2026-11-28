package com.raven.app.core.media.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import androidx.core.graphics.scale
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Suspending OkHttp call + bitmap decoding helpers shared by the gallery page repository.
 *
 * Extracted from `GalleryPageRepository` as stateless functions so they can be reused by download
 * and cache collaborators without dragging the repository state along.
 */

internal suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                    } else {
                        cont.resume(response)
                    }
                }
            })
        }

internal fun decodeSampledBitmap(bytes: ByteArray, maxPixels: Long): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图源未返回可识别的图片，请重试此页" }
        val sample = ImageStructureValidator.computeInSampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("图片解码失败，请重试此页")
    }

internal fun decodeThumbnailBitmap(bytes: ByteArray, targetSizePx: Int): Bitmap? {
        if (targetSizePx <= 0) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val width = opts.outWidth
        val height = opts.outHeight
        if (width <= 0 || height <= 0) return null
        val longEdge = maxOf(width, height)
        opts.inJustDecodeBounds = false
        opts.inSampleSize = ImageStructureValidator.computeThumbnailSampleSize(longEdge, targetSizePx)
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val dstW = ImageStructureValidator.computeThumbnailEdge(width, longEdge, targetSizePx)
        val dstH = ImageStructureValidator.computeThumbnailEdge(height, longEdge, targetSizePx)
        if (sampled.width == dstW && sampled.height == dstH) return sampled
        return try {
            sampled.scale(dstW, dstH, true)
        } finally {
            sampled.recycle()
        }
    }
