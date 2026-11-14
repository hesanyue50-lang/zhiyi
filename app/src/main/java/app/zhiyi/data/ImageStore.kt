package app.zhiyi.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/** 多模态图片：压缩落盘 + base64 data URI，全部在 App 私有目录，随 App 删除 */
object ImageStore {

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "images")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 从 content:// URI 读入、缩到最长边 1280、JPEG 落盘，返回相对文件名 */
    fun save(ctx: Context, uri: Uri): String? = runCatching {
        val ins = ctx.contentResolver.openInputStream(uri) ?: return@runCatching null
        val bmp0 = BitmapFactory.decodeStream(ins)
        ins.close()
        if (bmp0 == null) return@runCatching null
        val maxSide = 1280
        val w = bmp0.width; val h = bmp0.height
        val scale = maxSide.toFloat() / maxOf(w, h)
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp0, (w * scale).toInt(), (h * scale).toInt(), true)
        else bmp0
        if (bmp !== bmp0) bmp0.recycle()
        val name = "img_${System.currentTimeMillis()}_${(bmp.width * 31 + bmp.height * 17) % 100000}.jpg"
        FileOutputStream(File(dir(ctx), name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle()
        name
    }.getOrNull()

    fun abs(ctx: Context, name: String): File = File(dir(ctx), name)

    fun toDataUri(ctx: Context, name: String): String? = runCatching {
        val f = File(dir(ctx), name)
        if (!f.exists()) return@runCatching null
        "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** 删掉某条消息的图片（撤回时调用） */
    fun delete(ctx: Context, names: List<String>) {
        names.forEach { runCatching { File(dir(ctx), it).delete() } }
    }
}
