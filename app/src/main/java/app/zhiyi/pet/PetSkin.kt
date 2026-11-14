package app.zhiyi.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.net.Uri
import app.zhiyi.R
import java.io.File

/**
 * 桌宠皮肤管理。
 * 内置一套（res/drawable），用户导入的放在 filesDir/pet_skins/<id>/<face>.png。
 * 皮肤可绑定人格（personaId），绑定后该桌宠对话走那个人格。
 */
object PetSkin {

    val FACES = listOf("calm", "happy", "blink", "angry", "sad", "surprised", "sleepy", "shy")
    const val BUILTIN = "elysia_q"          // 唯一的内置皮肤

    // Drawable 缓存（避免每次 setFace 重复 decode 文件）
    private val cache = LruCache<String, Drawable>(64)

    private val builtin = mapOf(
        "calm" to R.drawable.pet_calm, "happy" to R.drawable.pet_happy,
        "blink" to R.drawable.pet_blink, "angry" to R.drawable.pet_angry,
        "sad" to R.drawable.pet_sad, "surprised" to R.drawable.pet_surprised,
        "sleepy" to R.drawable.pet_sleepy, "shy" to R.drawable.pet_shy,
    )

    private fun isBuiltin(id: String) = id == BUILTIN || id.isBlank()

    data class Skin(
        val id: String, val name: String, val dir: File?, val personaId: String,
        val keywords: List<String> = emptyList(),
    )

    fun root(ctx: Context): File = File(ctx.filesDir, "pet_skins").apply { mkdirs() }

    fun list(ctx: Context): List<Skin> {
        // 内置皮肤自带角色关键词（用于提示/未来自动识别）
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val out = mutableListOf(
            Skin(
                BUILTIN, "爱莉希雅·Q版", null,
                prefs.getString("builtin_skin_persona", "") ?: "",
                listOf("爱莉希雅", "Elysia", "爱莉", "粉毛", "精灵耳", "始源", "人之律者"),
            ),
        )
        root(ctx).listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val meta = File(d, "meta.txt")
            val name = if (meta.exists()) meta.readText().lines().firstOrNull().orEmpty().ifBlank { d.name } else d.name
            val lines = if (meta.exists()) meta.readText().lines() else emptyList()
            val pid = lines.getOrNull(1).orEmpty()
            val kws = lines.getOrNull(2).orEmpty().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            out.add(Skin(d.name, name, d, pid, kws))
        }
        return out
    }

    fun face(ctx: Context, skinId: String, face: String): Drawable? {
        val f = face.coerceIn()
        val key = "$skinId/$f"
        cache.get(key)?.let { return it }
        val d: Drawable? = if (isBuiltin(skinId)) {
            ctx.getDrawable(builtin[f] ?: builtin["calm"]!!)
        } else {
            val file = File(File(root(ctx), skinId), "$f.png")
            val fallback = builtin[f] ?: builtin["calm"]!!
            if (!file.exists()) ctx.getDrawable(fallback)   // 缺图回落内置
            else {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp == null) ctx.getDrawable(fallback)
                else BitmapDrawable(ctx.resources, bmp).apply { paint.isFilterBitmap = false }
            }
        }
        d?.let { cache.put(key, it) }
        return d
    }

    /** 导入/删除后清缓存 */
    fun invalidate() = cache.evictAll()

    private fun String.coerceIn(): String = if (this in FACES) this else "calm"

    /** 导入：显式指定每张图对应哪个表情 */
    fun importMapped(ctx: Context, name: String, personaId: String, items: List<Pair<Uri, String>>): String {
        val id = "skin_" + System.currentTimeMillis().toString(36)
        val dir = File(root(ctx), id).apply { mkdirs() }
        items.forEach { (uri, face) ->
            val f = if (face in FACES) face else "calm"
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    File(dir, "$f.png").outputStream().use { ins.copyTo(it) }
                }
            }
        }
        File(dir, "meta.txt").writeText("$name\n$personaId\n")
        cache.evictAll()
        return id
    }

    /** 按文件名猜表情（导入对话框的默认值） */
    fun guessFace(displayName: String): String {
        val lower = displayName.lowercase()
        return FACES.firstOrNull { lower.contains(it) } ?: "calm"
    }

    /** 导入：把选中的图片按文件名对应表情存成新皮肤。文件名含 calm/happy 等即匹配。 */
    fun import(ctx: Context, name: String, personaId: String, uris: List<Uri>): String {
        val id = "skin_" + System.currentTimeMillis().toString(36)
        val dir = File(root(ctx), id).apply { mkdirs() }
        uris.forEach { uri ->
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: ""
            val lower = name.lowercase()
            val face = FACES.firstOrNull { lower.contains(it) } ?: return@forEach
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    File(dir, "$face.png").outputStream().use { ins.copyTo(it) }
                }
            }
        }
        File(dir, "meta.txt").writeText("$name\n$personaId")
        cache.evictAll()
        return id
    }

    /** 给皮肤绑定人格（空串=不绑定，用当前人格） */
    fun setPersona(ctx: Context, skinId: String, personaId: String) {
        if (skinId == BUILTIN) {
            // 内置皮肤没有目录，绑定关系存 prefs
            ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("builtin_skin_persona", personaId).commit()
            return
        }
        val dir = File(root(ctx), skinId)
        val meta = File(dir, "meta.txt")
        val lines = if (meta.exists()) meta.readText().lines() else emptyList()
        val name = lines.firstOrNull().orEmpty().ifBlank { skinId }
        val kws = lines.getOrNull(2).orEmpty()
        meta.writeText("$name\n$personaId\n$kws")
        cache.evictAll()
    }

    /** 设置皮肤关键词（逗号分隔） */
    fun setKeywords(ctx: Context, skinId: String, kws: String) {
        if (skinId == BUILTIN) return
        val dir = File(root(ctx), skinId)
        val meta = File(dir, "meta.txt")
        val lines = if (meta.exists()) meta.readText().lines() else emptyList()
        val name = lines.firstOrNull().orEmpty().ifBlank { skinId }
        val pid = lines.getOrNull(1).orEmpty()
        meta.writeText("$name\n$pid\n$kws")
    }

    fun delete(ctx: Context, skinId: String) {
        if (skinId == BUILTIN) return
        runCatching { File(root(ctx), skinId).deleteRecursively() }
        cache.evictAll()
    }
}
