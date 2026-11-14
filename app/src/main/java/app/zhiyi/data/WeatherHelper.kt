package app.zhiyi.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 天气感知，多重兜底：
 * 1. 中国天气网（国家平台，国内直连）：城市名 → city3jdata 三级列表查 9 位 code → d1.weather.com.cn 实况
 * 2. Open-Meteo（海外兜底，geocoding 国内常被墙，所以放最后）
 * 结果缓存 30 分钟。
 */
object WeatherHelper {

    private val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
    private val REF = "http://www.weather.com.cn/"

    // 直辖市（city3jdata 里它们的市序号是 "00"，直接硬编码更稳）
    private val DIRECT = mapOf(
        "北京" to "101010100", "上海" to "101020100",
        "天津" to "101030100", "重庆" to "101040100",
    )

    /** 读缓存（30 分钟有效，不碰网络）。拼装上下文时用，保证不阻塞回复 */
    fun cached(db: Db, city: String): String? {
        if (city.isBlank()) return null
        val c = db.get("weather_cache", "")
        val city0 = db.get("weather_city", "")
        val ts = db.getNum("weather_ts", 0)
        if (c.isBlank() || city0 != city || System.currentTimeMillis() - ts > 30 * 60_000L) return null
        return c
    }

    /** 拉网络刷新缓存（后台调，失败静默）。先国家平台，再 Open-Meteo */
    fun refresh(db: Db, city: String) {
        if (city.isBlank()) return
        val key = city.trim().removeSuffix("市").removeSuffix("省")
            .replace("特别行政区", "").replace("自治区", "").replace("维吾尔", "").replace("回族", "").replace("壮族", "")
        db.put("cityneg_$key", "")   // 手动/主动刷新时清掉负缓存，重试一次
        val desc = fetchCma(db, key) ?: fetchOpenMeteo(key) ?: return
        db.put("weather_cache", desc)
        db.put("weather_city", city)
        db.put("weather_ts", System.currentTimeMillis().toString())
    }

    // ---------------- 兜底 1：中国天气网 ----------------

    private fun fetchCma(db: Db, city: String): String? {
        val code = resolveCode(db, city) ?: return null
        val txt = httpGet("https://d1.weather.com.cn/sk_2d/$code.html") ?: return null
        val json = txt.substringAfter("=", "").trim().trimEnd(';')
        if (!json.startsWith("{")) return null
        return runCatching {
            val o = JSONObject(json)
            val temp = o.optString("temp", "").trim()
            val weather = o.optString("weather", "").trim()
            if (temp.isBlank() || weather.isBlank()) return null
            val t = temp.toDoubleOrNull()?.toInt() ?: return null
            "$weather ${t}°C"
        }.getOrNull()
    }

    /** 城市名 → 9 位 code：直辖市直查，其他走 省→市→区县 三级 */
    private fun resolveCode(db: Db, city: String): String? {
        DIRECT[city]?.let { return it }
        val cache = db.get("citycode_$city", "")
        if (cache.isNotBlank()) return cache
        // 负缓存：24 小时内没匹配到的，不再全量扫省表
        val neg = db.get("cityneg_$city", "")
        if (neg.isNotBlank() && System.currentTimeMillis() - (neg.toLongOrNull() ?: 0) < 3_600_000L) return null

        val provTxt = httpGet("https://www.weather.com.cn/data/city3jdata/china.html") ?: return null
        val prov = runCatching { JSONObject(provTxt) }.getOrNull() ?: return null
        for (pcode in prov.keys()) {
            val pname = prov.optString(pcode, "")
            // 直辖市已在 DIRECT 处理过，这里只找地级市
            val cityTxt = httpGet("https://www.weather.com.cn/data/city3jdata/provshi/$pcode.html") ?: continue
            val cities = runCatching { JSONObject(cityTxt) }.getOrNull() ?: continue
            for (cidx in cities.keys()) {
                if (cities.optString(cidx, "").removeSuffix("市") == city) {
                    val city7 = pcode + cidx
                    // 区县：取第一个（通常是市区）
                    val stTxt = httpGet("https://www.weather.com.cn/data/city3jdata/station/$city7.html")
                    val st = stTxt?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val firstIdx = st?.keys()?.asSequence()?.firstOrNull() ?: "01"
                    val code = city7 + firstIdx
                    // station 成功才缓存，失败不固化错误 code
                    if (st != null) db.put("citycode_$city", code)
                    return code
                }
            }
        }
        db.put("cityneg_$city", System.currentTimeMillis().toString())
        return null
    }

    private fun httpGet(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", REF)
        c.setRequestProperty("Accept", "*/*")
        val code = c.responseCode
        if (code !in 200..299) return null
        c.inputStream.bufferedReader().readText()
    }.getOrNull()

    // ---------------- 兜底 2：Open-Meteo ----------------

    private fun fetchOpenMeteo(city: String): String? {
        val ll = geocode(city) ?: return null
        val (lat, lon) = ll
        return runCatching {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val cur = o.optJSONObject("current") ?: return null
            val t = cur.optDouble("temperature_2m", -999.0).toInt()
            val code = cur.optInt("weather_code", -1)
            if (t < -900 || code < 0) return null
            "${codeDesc(code)} ${t}°C"
        }.getOrNull()
    }

    private fun geocode(city: String): Pair<Double, Double>? = runCatching {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(city, "UTF-8") + "&count=1&language=zh"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val first = o.optJSONArray("results")?.optJSONObject(0) ?: return@runCatching null
        val lat = first.optDouble("latitude", Double.NaN)
        val lon = first.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return@runCatching null
        lat to lon
    }.getOrNull()

    private fun codeDesc(code: Int): String = when (code) {
        0 -> "晴"; 1 -> "晴间多云"; 2 -> "多云"; 3 -> "阴"
        45, 48 -> "雾"; 51, 53, 55 -> "毛毛雨"; 61, 63, 65 -> "雨"; 71, 73, 75 -> "雪"
        80, 81, 82 -> "阵雨"; 95 -> "雷雨"; else -> "多云"
    }
}
