package app.zhiyi.data

import android.content.Context
import app.zhiyi.pet.PetSkin
import app.zhiyi.voice.VoiceStore
import java.io.File
import java.util.Locale

/**
 * 「清理空间」的账本。
 *
 * 只做两件事：**算清每一项占了多少**、**把删除动作收敛成几个幂等的函数**。
 * 界面只管展示和确认，不直接碰文件和表——否则清理逻辑会散在四五个地方，
 * 加一个新的存储（比如以后的缓存模型）就得改三处。
 *
 * 原则：
 * 1. 每一项都要能**单独删**，不要只给"全部清空"。用户删记忆往往只想删某一个人的。
 * 2. 删除一律**可重入**（文件不存在 / 表里没记录都不报错），失败也只吞掉不中断整批。
 * 3. 人格、消息、记忆是**三样不同的东西**：清记忆不等于删人格，删人格才连带。
 */

/** 一块可清理的占用 */
data class Usage(
    val key: String,
    val label: String,
    val detail: String,
    val bytes: Long,
)

/** 一个人格名下的账 */
data class PersonaUsage(
    val pid: String,
    val name: String,
    val msgs: Int,
    val facts: Int,
    val episodes: Int,
    val summaries: Int,
    val voiceDetail: String,
    val voiceBytes: Long,
    val hasVoice: Boolean,
    val skins: List<String>,
) {
    val memoryCount: Int get() = facts + episodes + summaries
    val isEmpty: Boolean
        get() = msgs == 0 && memoryCount == 0 && !hasVoice && skins.isEmpty()
}

object CleanStore {

    // ---------------- 容量 ----------------

    fun dirBytes(f: File?): Long {
        if (f == null || !f.exists()) return 0
        if (f.isFile) return runCatching { f.length() }.getOrDefault(0)
        var sum = 0L
        runCatching { f.walkTopDown().forEach { if (it.isFile) sum += it.length() } }
        return sum
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun count(db: Db, table: String, pid: String): Int = runCatching {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE pid=?", arrayOf(pid)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }.getOrDefault(0)

    fun chatBytes(db: Db): Long = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(LENGTH(content)+LENGTH(IFNULL(images,''))),0) FROM msg", null,
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }.getOrDefault(0)

    fun totalMsgs(db: Db): Int = runCatching {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM msg", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }.getOrDefault(0)

    fun imageDir(ctx: Context): File = File(ctx.filesDir, "images")
    fun orphanImages(ctx: Context, db: Db): List<File> {
        val dir = imageDir(ctx)
        if (!dir.exists()) return emptyList()
        val used = HashSet<String>()
        runCatching {
            db.readableDatabase.rawQuery(
                "SELECT images FROM msg WHERE images IS NOT NULL AND images<>''", null,
            ).use { c ->
                while (c.moveToNext()) {
                    val s = c.getString(0) ?: continue
                    Regex("\"([^\"]+)\"").findAll(s).forEach { used.add(it.groupValues[1].substringAfterLast('/')) }
                }
            }
        }
        return dir.listFiles()?.filter { it.isFile && it.name !in used } ?: emptyList()
    }

    /** 顶部总览：每块占多少 */
    fun overview(ctx: Context, db: Db): List<Usage> {
        val orphFiles = orphanImages(ctx, db)
        val orphBytes = orphFiles.sumOf { it.length() }
        return listOf(
            Usage("chat", "聊天记录", "${totalMsgs(db)} 条", chatBytes(db)),
            Usage("voice", "声音样本", "人格用的人声", dirBytes(VoiceStore.dir(ctx))),
            Usage("skin", "桌宠立绘", "导入的形象", dirBytes(PetSkin.root(ctx))),
            Usage("sticker", "表情", "聊天里抓来的图", dirBytes(StickerStore.dir(ctx))),
            Usage("image", "聊天图片", "${orphFiles.size} 张已经没人用了", orphBytes),
            Usage("cache", "临时缓存", "随时可以清", dirBytes(ctx.cacheDir)),
        )
    }

    // ---------------- 每人格的账 ----------------

    fun personaUsage(ctx: Context, db: Db, p: Persona): PersonaUsage {
        val f = VoiceStore.userFile(ctx, p.id)
        val preset = VoiceStore.currentPreset(ctx, p.id)
        val hasVoice = (f != null && f.exists()) || preset.isNotBlank()
        val vd = when {
            f != null && f.exists() -> "自传样本 ${human(f.length())}"
            preset.isNotBlank() -> "预置音色"
            else -> "未设置声音"
        }
        return PersonaUsage(
            pid = p.id,
            name = p.name,
            msgs = db.msgCount(p.id),
            facts = count(db, "fact", p.id),
            episodes = count(db, "episode", p.id),
            summaries = count(db, "summary", p.id),
            voiceDetail = vd,
            voiceBytes = if (f != null && f.exists()) f.length() else 0L,
            hasVoice = hasVoice,
            skins = PetSkin.list(ctx).filter { it.personaId == p.id }.map { it.name },
        )
    }

    // ---------------- 删除动作（全部幂等） ----------------

    /** 只删记忆：事实 / 往事 / 摘要 + 相关的键。不动消息、不动人格 */
    fun clearMemory(db: Db, pid: String) = runCatching { db.clearMemory(pid) }

    /** 只删聊天记录 */
    fun clearChat(db: Db, pid: String) = runCatching { db.clearMsgs(pid) }

    /** 删声音：样本文件 + 预置音色选择 */
    fun clearVoice(ctx: Context, pid: String) = runCatching { VoiceStore.clear(ctx, pid) }

    /** 删这个人人格名下的桌宠立绘（内置那套删不掉，也不该删） */
    fun clearSkins(ctx: Context, pid: String): Int {
        var n = 0
        runCatching {
            PetSkin.list(ctx).filter { it.personaId == pid && it.dir != null }.forEach {
                PetSkin.delete(ctx, it.id); n++
            }
        }
        return n
    }

    fun clearOrphanImages(ctx: Context, db: Db): Long {
        var freed = 0L
        orphanImages(ctx, db).forEach { freed += it.length(); runCatching { it.delete() } }
        return freed
    }

    /** 删人格：人格 + 消息 + 记忆 + 声音 + 立绘，彻底不留 */
    fun clearPersona(ctx: Context, db: Db, pid: String) {
        runCatching { clearVoice(ctx, pid) }
        runCatching { clearSkins(ctx, pid) }
        runCatching { db.deletePersonaAll(pid) }
    }
}
