package com.pleos.cardex.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap

/**
 * 쇼룸 무료 에셋 (앱 assets 에 포함, 네트워크 불필요)
 * - 바닥 질감: Poly Haven (CC0) — assets/textures/{concrete,asphalt,wood,marble}.jpg
 * - 소품 아이콘: Twemoji (그래픽 CC BY 4.0, © Twitter/X 및 기여자) — assets/twemoji/<코드포인트>.png
 * 출처·라이선스: docs/ASSET_CREDITS.md
 */
object ShowroomAssets {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun load(context: Context, path: String): ImageBitmap? {
        cache[path]?.let { return it }
        if (path in missing) return null
        return runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }.asImageBitmap()
        }.getOrNull()?.also { cache[path] = it } ?: run { missing += path; null }
    }

    /** 바닥 질감 (checker 는 콘크리트 위에 체커를 그린다, neon 은 질감 없음) */
    fun floorTexture(context: Context, floorKey: String): ImageBitmap? = when (floorKey) {
        "neon" -> null
        "checker" -> load(context, "textures/concrete.jpg")
        else -> load(context, "textures/$floorKey.jpg") ?: load(context, "textures/concrete.jpg")
    }

    /** Twemoji 파일명 규칙: 코드포인트 16진수를 '-' 로 잇고, ZWJ(200d)가 없으면 FE0F 는 뺀다 */
    fun twemojiCode(emoji: String): String {
        val cps = emoji.codePoints().toArray().map { Integer.toHexString(it) }
        return (if ("200d" in cps) cps else cps.filter { it != "fe0f" }).joinToString("-")
    }

    fun twemoji(context: Context, emoji: String): ImageBitmap? = load(context, "twemoji/${twemojiCode(emoji)}.png")
}

/** 기기 폰트와 무관하게 같은 그림체로 보이는 이모지 (Twemoji). 없으면 글자로 */
@Composable
fun EmojiIcon(emoji: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp = remember(emoji) { ShowroomAssets.twemoji(ctx, emoji) }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = modifier.size(size))
    else Text(emoji, fontSize = (size.value * 0.8f).sp, modifier = modifier)
}

