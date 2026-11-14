package app.zhiyi.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import java.io.File
import java.io.FileOutputStream

/**
 * MediaProjection 截屏（常驻 VirtualDisplay）。
 * Android 14+ 一个 MediaProjection 只能 createVirtualDisplay 一次，
 * 所以授权后建一次常驻，之后反复 acquireLatestImage 取帧。
 */
object ScreenCapture {

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var vd: VirtualDisplay? = null
    @Volatile private var w = 0
    @Volatile private var h = 0

    private val cb = object : MediaProjection.Callback() {
        override fun onStop() { runCatching { ScreenCapture.stop() } }
    }

    fun setProjection(p: MediaProjection?, ctx: Context? = null) {
        stop()
        if (p == null) return
        // Android 14+ 必须先注册回调再 createVirtualDisplay，否则 IllegalStateException
        runCatching { p.registerCallback(cb, android.os.Handler(android.os.Looper.getMainLooper())) }
        projection = p
        // 不在这里 build：Android 16 要求 createVirtualDisplay 时前台服务已是 mediaProjection 类型，
        // 由 captureToCache 的调用方（PetService）先升级前台类型，再进这里 build。
    }

    private fun build(ctx: Context) {
        val p = projection ?: return
        val dm = ctx.resources.displayMetrics
        val nw = dm.widthPixels; val nh = dm.heightPixels
        var r: ImageReader? = null
        try {
            r = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 3)
            val v = p.createVirtualDisplay(
                "zhiyi_cap", nw, nh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            // 全部成功后才落字段，避免半失败留下脏状态
            vd = v; reader = r; w = nw; h = nh
        } catch (e: Throwable) {
            android.util.Log.e("ScreenCapture", "build 失败", e)
            runCatching { r?.close() }
            vd = null; reader = null; w = 0; h = 0
        }
    }

    /** 必须投影和 vd 都在，否则"已授权但永远截不到" */
    // 原来要求 vd != null，而 vd 只在 captureToCache 里懒 build、captureToCache 又要先 isReady()
    // -> 互锁，projection 路线永远不可用。就绪只看有没有拿到授权。
    fun isReady(): Boolean = projection != null
    fun hasProjection(): Boolean = projection != null

    /** 截一帧存 cacheDir，失败 null */
    fun captureToCache(ctx: Context, name: String = "pet_shot.png"): File? {
        if (projection == null) return null
        if (reader == null) runCatching { build(ctx) }
        // 旋转/分屏后尺寸会变：先 resize，否则截图被压扁
        runCatching { ensureSize(ctx) }
        val r = reader ?: return null
        val lw = w; val lh = h
        var image: android.media.Image? = null
        var bmp: Bitmap? = null
        return try {
            val deadline = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < deadline) {
                image = r.acquireLatestImage()
                if (image != null) break
                Thread.sleep(50)
            }
            if (image == null) return null
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * lw
            val padded = Bitmap.createBitmap(lw + rowPadding / pixelStride, lh, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (padded.width != lw) {
                bmp = Bitmap.createBitmap(padded, 0, 0, lw, lh)
                padded.recycle()
            } else bmp = padded
            val f = File(ctx.cacheDir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            f
        } catch (e: Throwable) {
            android.util.Log.e("ScreenCapture", "capture 失败", e); null
        } finally {
            runCatching { image?.close() }      // 关键：任何路径都释放 Image，否则 3 次后永久失效
            runCatching { bmp?.recycle() }
        }
    }

    /** displayMetrics 变了就 resize VirtualDisplay + 换 reader（14+ 不能重建 VirtualDisplay） */
    private fun ensureSize(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val nw = dm.widthPixels; val nh = dm.heightPixels
        if (nw == w && nh == h) return
        val v = vd ?: return
        val nr = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 3)
        v.resize(nw, nh, dm.densityDpi)
        v.surface = nr.surface
        runCatching { reader?.close() }
        reader = nr
        w = nw; h = nh
    }

    fun stop() {
        runCatching { vd?.release() }; vd = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
    }

    // ---------- 路线 B：Shizuku / ADB 截图（不需要录屏授权） ----------
    fun shizukuReady(): Boolean = try {
        rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun shizukuAvailable(): Boolean = try { rikka.shizuku.Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun captureAdb(ctx: Context, name: String = "pet_shot.png"): File? {
        return try {
            if (!shizukuReady()) return null
            // 关键：shell 用户写不了 App 私有 cacheDir，所以让 screencap 输出到 stdout，App 自己落盘
            val strArr = Class.forName("[Ljava.lang.String;")
            val m = rikka.shizuku.Shizuku::class.java
                .getDeclaredMethod("newProcess", strArr, strArr, String::class.java)
            m.isAccessible = true
            val pb = ProcessBuilder("screencap", "-p")
            val proc = m.invoke(null, arrayOf("screencap", "-p"), null, null) as Process
            // 读 + 等待都带超时，否则卡一次 busy 永久为 true，桌宠变哑巴
            var bytes = ByteArray(0)
            val t = Thread {
                runCatching { bytes = proc.inputStream.use { it.readBytes() } }
            }
            t.start(); t.join(3000)
            if (t.isAlive) { runCatching { proc.destroy() }; return null }
            runCatching { proc.waitFor() }
            if (bytes.size < 1000) {
                android.util.Log.e("ScreenCapture", "adb capture 返回空 (${bytes.size}B)")
                return null
            }
            val f = File(ctx.cacheDir, name)
            f.writeBytes(bytes)
            f
        } catch (e: Throwable) {
            android.util.Log.e("ScreenCapture", "adb capture 失败", e); null
        }
    }

    /**
     * 视频模式专用：截一帧直接解成缩小的 Bitmap，不落盘。
     * 每 500ms 一帧、20 帧一轮，走 File 会多两次全量 IO + PNG 解码，跟不上。
     */
    fun captureBitmap(ctx: Context, maxWidth: Int = 180): android.graphics.Bitmap? {
        val bytes = if (shizukuReady()) adbBytes() else projectionBytes(ctx)
        if (bytes == null || bytes.size < 800) return null
        return try {
            val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
            var sample = 1
            while (opt.outWidth / sample > maxWidth * 2) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            if (bmp.width <= maxWidth) bmp else {
                val w = maxWidth
                val h = (bmp.height.toFloat() * w / bmp.width).toInt().coerceAtLeast(1)
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                if (small !== bmp) bmp.recycle()
                small
            }
        } catch (e: Throwable) {
            android.util.Log.w("ScreenCapture", "captureBitmap 解码失败", e)
            null
        }
    }

    private fun adbBytes(): ByteArray? {
        return try {
            if (!shizukuReady()) return null
            val strArr = Class.forName("[Ljava.lang.String;")
            val m = rikka.shizuku.Shizuku::class.java
                .getDeclaredMethod("newProcess", strArr, strArr, String::class.java)
            m.isAccessible = true
            val proc = m.invoke(null, arrayOf("screencap", "-p"), null, null) as Process
            var bytes = ByteArray(0)
            val t = Thread { runCatching { bytes = proc.inputStream.use { it.readBytes() } } }
            t.start(); t.join(2500)
            if (t.isAlive) { runCatching { proc.destroy() }; return null }
            runCatching { proc.waitFor() }
            if (bytes.size < 1000) null else bytes
        } catch (e: Throwable) {
            null
        }
    }

    /** 投影路线：直接拿最新一帧的像素，比走 File 快 */
    private fun projectionBytes(ctx: Context): ByteArray? {
        if (projection == null) return null
        if (reader == null) runCatching { build(ctx) }
        val r = reader ?: return null
        var img: android.media.Image? = null
        return try {
            img = r.acquireLatestImage() ?: return null
            val plane = img.planes[0]
            val buf = plane.buffer
            val px = IntArray(buf.remaining() / 4)
            buf.asIntBuffer().get(px)
            val bmp = android.graphics.Bitmap.createBitmap(px, plane.rowStride / 4, img.height, android.graphics.Bitmap.Config.ARGB_8888)
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            bos.toByteArray()
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { img?.close() }
        }
    }

    fun cleanTemp(ctx: Context) {
        runCatching {
            ctx.cacheDir.listFiles()?.filter { it.name.startsWith("pet_shot") }?.forEach { it.delete() }
        }
    }
}
