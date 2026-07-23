package com.retrofm.android.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import timber.log.Timber

/**
 * Wraps the session's [BitmapLoader] with DEBUG/WARN logging. Field motivation: the car's
 * now-playing view showed an artwork placeholder while the phone (which loads artwork itself
 * via Coil) was fine — this makes the session-side artwork path observable through the log
 * sink: is artwork requested at all, and does loading succeed?
 */
class ArtworkLoggingBitmapLoader(private val delegate: BitmapLoader) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        Timber.tag("Artwork").d("loadBitmap %s", uri)
        val future = delegate.loadBitmap(uri)
        Futures.addCallback(
            future,
            object : FutureCallback<Bitmap> {
                override fun onSuccess(result: Bitmap) {
                    Timber.tag("Artwork").d("loaded %dx%d for %s", result.width, result.height, uri)
                }

                override fun onFailure(t: Throwable) {
                    Timber.tag("Artwork").w("load FAILED for %s: %s", uri, t.toString())
                }
            },
            MoreExecutors.directExecutor()
        )
        return future
    }
}
