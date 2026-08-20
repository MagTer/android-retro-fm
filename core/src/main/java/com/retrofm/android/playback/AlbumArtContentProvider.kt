package com.retrofm.android.playback

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.retrofm.android.core.R
import com.retrofm.android.data.config.RetroFmConfig
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves remote album art to Android Automotive OS through a local content:// URI.
 *
 * AAOS only accepts LOCAL artwork URIs (content:// or android.resource://) for the now-playing
 * and browse surfaces — remote https:// URIs and embedded bitmaps (setIconBitmap/artworkData)
 * are silently ignored, which is why the car showed the placeholder while the phone (Coil) and
 * the media notification (Media3's own BitmapLoader) rendered the same https art fine. See
 * developer.android.com/training/cars/media/create-media-browser/media-artwork.
 *
 * The remote URL is base64url-encoded into the content path, so [openFile] reconstructs it with
 * no in-memory map — the car's media host (a separate process) can read it, and it survives a
 * process restart. Downloads are cached under cacheDir; only allowlisted hosts are fetched, so
 * a crafted content:// URI can't turn this into an open proxy.
 */
class AlbumArtContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.magter.retrofm.artwork"
        /**
         * Hosts this proxy will fetch. Not decoration — a crafted content:// URI would
         * otherwise turn the provider into an open proxy.
         *
         * `mzstatic.com` is Apple's artwork CDN, which serves the covers ArtworkLookup
         * resolves; it is spread over `is1-ssl`…`is5-ssl` subdomains, hence the suffix match
         * below. Forgetting it in 1.0.41 was the whole "car shows a placeholder with two
         * circles" bug: openFile blocked every cover and returned null. **Any new artwork
         * source needs its host added here or it silently renders nothing.**
         */
        private val ALLOWED_HOSTS = setOf(
            "media.bauerradio.com",
            "assets.planetradio.co.uk",
            "mzstatic.com",
            // The station's own covers (StationNowPlaying), served from /nowPlayingMedia/.
            "retrofm.se"
        )
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        /** Maps a remote art URI to a content:// URI this provider serves. Context-free. */
        fun mapUri(remote: Uri): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .appendPath(Base64.encodeToString(remote.toString().toByteArray(), B64))
            .build()

        private fun decode(token: String): String? =
            runCatching { String(Base64.decode(token, B64)) }.getOrNull()

        /**
         * Short, human-readable form of one of our content:// URIs, for logging.
         *
         * The raw URI is the remote URL base64'd into the path — ~300 characters of noise per
         * line, and two lines per bitmap load. That is not just unreadable: it is what filled
         * the log batches that a 4 KB proxy body limit then rejected (2026-08-09). Log lines
         * are shipped over the wire, so their size is a real cost, not a cosmetic one.
         */
        fun describe(uri: Uri): String {
            if (uri.authority != AUTHORITY) return uri.toString()
            val remote = uri.lastPathSegment?.let { decode(it) } ?: return "content:(undecodable)"
            return Uri.parse(remote).let { "${it.host}${it.lastPathSegment?.let { s -> "/$s" }.orEmpty()}" }
        }
    }

    // Per-image locks so concurrent requests for the same art download it once (see openFile).
    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockFor(token: String): Any = locks.getOrPut(token) { Any() }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val token = uri.lastPathSegment ?: return null
        val remote = decode(token) ?: return null

        val host = runCatching { Uri.parse(remote).host }.getOrNull()
        if (host == null || ALLOWED_HOSTS.none { host == it || host.endsWith(".$it") }) {
            Timber.tag("Artwork").w("blocked non-allowlisted art host: %s", host)
            return null
        }

        val cacheDir = File(ctx.cacheDir, "albumart").apply { mkdirs() }
        val file = File(cacheDir, token)
        // Serialize concurrent requests for the SAME image. The car asks from several surfaces
        // at once (now-playing, browse tile, launcher); they previously raced on a shared temp
        // file, so one download won and the rest failed the rename — logging a spurious
        // "download failed" and returning no bitmap to that surface. One download, the rest read
        // the cache. Each download uses a unique temp file so a rename never collides.
        synchronized(lockFor(token)) {
            if (!file.exists() || file.length() == 0L) {
                val tmp = File.createTempFile(token.take(40), ".tmp", cacheDir)
                val ok = runCatching {
                    if (remote == RetroFmConfig.LOGO_PNG_URL) {
                        // The station logo ships inside the APK: browse tiles, the idle state
                        // and the ad card must render with ZERO network — the car asks for
                        // them at boot, before the user presses play and often before the
                        // modem has real internet.
                        ctx.resources.openRawResource(R.raw.station_logo)
                            .use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    } else {
                        (URL(remote).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5_000
                            readTimeout = 5_000
                        }.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    }
                    tmp.renameTo(file)
                }.getOrDefault(false)
                if (!ok) tmp.delete()
                if (!file.exists() || file.length() == 0L) {
                    Timber.tag("Artwork").w("content-provider download failed for %s", host)
                    return null
                }
                Timber.tag("Artwork").d("content-provider cached %d bytes from %s", file.length(), host)
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val remote = uri.lastPathSegment?.let { decode(it) }
        return if (remote?.endsWith(".png", ignoreCase = true) == true) "image/png" else "image/jpeg"
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
