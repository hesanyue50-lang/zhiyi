package app.zhiyi.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题 token。字段是可观察的 Compose 状态，
 * 所以 T.apply(dark) 一调，所有读 T.xxx 的地方自动重组 —— 组件代码里不用写任何判断。
 */
object T {

    var isDark by mutableStateOf(true); private set

    // 页面与层级
    var bg by mutableStateOf(Color(0xFF0A0B0F)); private set
    var bgElev by mutableStateOf(Color(0xFF101218)); private set
    var glass by mutableStateOf(Color(0xF212141A)); private set

    // 卡片叠层（两段渐变 + 描边）
    var cardTop by mutableStateOf(Color(0x0DFFFFFF)); private set
    var cardBot by mutableStateOf(Color(0x05FFFFFF)); private set
    var hi1 by mutableStateOf(Color(0x14FFFFFF)); private set
    var hi2 by mutableStateOf(Color(0x0AFFFFFF)); private set
    var fieldBg by mutableStateOf(Color(0x0FFFFFFF)); private set
    var track by mutableStateOf(Color(0x1AFFFFFF)); private set
    var handle by mutableStateOf(Color(0x33FFFFFF)); private set

    // 描边
    var stroke by mutableStateOf(Color(0x14FFFFFF)); private set
    var strokeSoft by mutableStateOf(Color(0x0DFFFFFF)); private set

    // 文字
    var text by mutableStateOf(Color(0xFFF5F7FA)); private set
    var textDim by mutableStateOf(Color(0x9EF5F7FA)); private set
    var textFaint by mutableStateOf(Color(0x61F5F7FA)); private set
    var onAccent by mutableStateOf(Color(0xFFF7F9FF)); private set

    // 强调与语义色
    var accent by mutableStateOf(Color(0xFF6C8CFF)); private set
    var accentDeep by mutableStateOf(Color(0xFF3D5BD9)); private set
    var accentTint by mutableStateOf(Color(0x2E6C8CFF)); private set
    var ok by mutableStateOf(Color(0xFF3ECF8E)); private set
    var warn by mutableStateOf(Color(0xFFF5A524)); private set
    var danger by mutableStateOf(Color(0xFFFF5C5C)); private set
    var dangerTint by mutableStateOf(Color(0x2AFF5C5C)); private set

    // 背景极光
    var glowA by mutableStateOf(Color(0x1F6C8CFF)); private set
    var glowB by mutableStateOf(Color(0x14A050FF)); private set
    var purple by mutableStateOf(Color(0xFFA050FF)); private set

    var scrim by mutableStateOf(Color(0x9E000000)); private set

    fun apply(dark: Boolean) {
        if (isDark == dark) return
        isDark = dark
        if (dark) {
            bg = Color(0xFF0A0D15); bgElev = Color(0xFF101521); glass = Color(0xF20E1420)
            cardTop = Color(0x0DD8E2FF); cardBot = Color(0x05C3D2FF)
            hi1 = Color(0x14CBD8FF); hi2 = Color(0x0AB8C6FF)
            fieldBg = Color(0x0FCBD8FF); track = Color(0x1ACBD8FF); handle = Color(0x33CBD8FF)
            stroke = Color(0x14CBD8FF); strokeSoft = Color(0x0DCBD8FF)
            text = Color(0xFFF5F7FA); textDim = Color(0x9EF5F7FA); textFaint = Color(0x61F5F7FA)
            onAccent = Color(0xFFF7F9FF)
            accent = Color(0xFF6C8CFF); accentDeep = Color(0xFF3D5BD9); accentTint = Color(0x2E6C8CFF)
            ok = Color(0xFF3ECF8E); warn = Color(0xFFF5A524); danger = Color(0xFFFF5C5C)
            dangerTint = Color(0x2AFF5C5C)
            glowA = Color(0x26708FFF); glowB = Color(0x18A050FF); purple = Color(0xFFA050FF)
            scrim = Color(0x9E000000)
        } else {
            bg = Color(0xFFF2F5FB); bgElev = Color(0xFFFFFFFF); glass = Color(0xF7FBFEFF)
            cardTop = Color(0xFFFFFFFF); cardBot = Color(0xFFF6F9FE)
            hi1 = Color(0x0F16223E); hi2 = Color(0x0816223E)
            fieldBg = Color(0xFFFFFFFF); track = Color(0x141A2740); handle = Color(0x331A2740)
            stroke = Color(0x131A2740); strokeSoft = Color(0x0A1A2740)
            text = Color(0xFF0B0E14); textDim = Color(0x9E0B0E14); textFaint = Color(0x610B0E14)
            onAccent = Color(0xFFFFFFFF)
            accent = Color(0xFF4166F5); accentDeep = Color(0xFF2E4ED0); accentTint = Color(0x244166F5)
            ok = Color(0xFF12A150); warn = Color(0xFFB4690E); danger = Color(0xFFE5484D)
            dangerTint = Color(0x1FE5484D)
            glowA = Color(0x144166F5); glowB = Color(0x0FA050FF); purple = Color(0xFF7A3BFF)
            scrim = Color(0x8A0B0E14)
        }
    }
}

/** 8pt 网格 + 固定圆角集合 */
object D {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp
    val s5 = 20.dp; val s6 = 24.dp; val s8 = 32.dp; val s10 = 40.dp
    val rSm = 8.dp; val rMd = 12.dp; val rCard = 20.dp; val rSheet = 28.dp
    val touch = 44.dp
    val page = 20.dp
}

/** 字阶：字号越大字距越负，越小越正 */
object Ty {
    val display = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
    val title1 = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val title2 = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.24).sp)
    val title3 = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val bodySm = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.12.sp)
    val label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
}

/** 弹簧：位移用 spatial（会过冲），颜色透明度用 effects（不越界） */
object Mo {
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> spatialDefault(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> spatialSlow(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> effectsFast(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> effectsDefault(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    /** 滑块/开关：位移过冲明显（≈ OvershootInterpolator(0.35)），dampingRatio 越低越弹 */
    fun <T> slider(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = 420f)
}
