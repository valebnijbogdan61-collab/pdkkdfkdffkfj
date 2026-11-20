package com.example.gamebutton.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/** Знімає екран через MediaProjection. Викликається з фонового потоку. */
class ScreenCapture(private val ctx: Context, private val mp: MediaProjection) {
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var w = 0
    private var h = 0
    private var last: Bitmap? = null

    init {
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = release()
        }, Handler(Looper.getMainLooper()))
    }

    @Synchronized
    fun grab(): Bitmap? {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        if (m.widthPixels != w || m.heightPixels != h) rebuild(m.widthPixels, m.heightPixels, m.densityDpi)
        val img = reader?.acquireLatestImage()
        if (img != null) {
            try { last = toBitmap(img) } finally { img.close() }
        }
        return last
    }

    private fun rebuild(nw: Int, nh: Int, dpi: Int) {
        val r = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 2)
        val d = vd
        if (d == null) {
            vd = mp.createVirtualDisplay(
                "gamebutton", nw, nh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
        } else {
            d.resize(nw, nh, dpi)
            d.surface = r.surface
        }
        reader?.close()
        reader = r
        w = nw; h = nh; last = null
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return if (pad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    @Synchronized
    fun release() {
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { mp.stop() }
        vd = null; reader = null; last = null
    }
}
