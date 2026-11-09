package com.pleos.cardex.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 제조사 공식 이미지로 '실제 차'를 보여주고 여러 각도로 돌려 본다.
 * - 목록: assets/vehicle_images.json (URL 만 보관. 이미지 파일은 저장소·APK에 넣지 않고 실행 중 내려받아 캐시)
 *   spin = 360° 프레임(현대 60장·기아 72장), gallery = 각도별 사진(제네시스·캐스퍼·카니발)
 * - 저작권은 각 제조사에 있다. 비상업 프로토타입 용도이며 상업화 전 사용 허락이 필요하다.
 * - 오프라인/로딩 전에는 코드로 만든 3D 모델([drawCar3D])을 대신 보여준다.
 */
sealed interface CarViews {
    data class Spin(val url: String, val frames: Int, val digits: Int, val start: Int) : CarViews {
        fun frameUrl(i: Int): String = url.replace("{n}", (((i % frames) + frames) % frames + 1).toString().padStart(digits, '0'))
    }
    data class Gallery(val urls: List<String>) : CarViews
}

object OfficialCarImages {
    @Volatile private var manifest: Map<String, CarViews>? = null
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    fun views(context: Context, id: String): CarViews? {
        val m = manifest ?: synchronized(this) {
            manifest ?: runCatching {
                val root = JSONObject(context.assets.open("vehicle_images.json").bufferedReader().use { it.readText() }).getJSONObject("vehicles")
                root.keys().asSequence().associateWith { k ->
                    val o = root.getJSONObject(k)
                    if (o.getString("type") == "spin") CarViews.Spin(o.getString("url"), o.getInt("frames"), o.getInt("digits"), o.optInt("start", 0))
                    else CarViews.Gallery((0 until o.getJSONArray("urls").length()).map { o.getJSONArray("urls").getString(it) })
                }
            }.getOrDefault(emptyMap()).also { manifest = it }
        }
        return m[id]
    }

    /** 디스크 캐시 파일 (한 번 본 각도는 오프라인에서도 보임) */
    fun cacheFile(context: Context, url: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "official_cars").apply { mkdirs() }, "$h.img")
    }

    fun cached(url: String): ImageBitmap? = memory[url]

    /** 차가 차지하는 영역 (투명 여백 제외) */
    private val bounds = ConcurrentHashMap<String, android.graphics.Rect>()
    fun bounds(url: String): android.graphics.Rect? = bounds[url]

    /** 밝은 단색 배경(흰/회색 스튜디오)이면 테두리에서부터 지워 투명하게 + 차 영역 계산 */
    private fun process(src: android.graphics.Bitmap): Pair<android.graphics.Bitmap, android.graphics.Rect> {
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun light(c: Int) = (c ushr 24) >= 24 && ((c shr 16) and 255) > 232 && ((c shr 8) and 255) > 232 && (c and 255) > 232
        fun bg(c: Int) = (c ushr 24) < 24 || light(c)
        val border = (0 until w step 7).flatMap { listOf(px[it], px[(h - 1) * w + it]) } + (0 until h step 7).flatMap { listOf(px[it * w], px[it * w + w - 1]) }
        if (border.all(::bg) && border.any(::light)) {
            val seen = BooleanArray(w * h); val q = java.util.ArrayDeque<Int>()
            for (x in 0 until w) { q.add(x); q.add((h - 1) * w + x) }
            for (y in 0 until h) { q.add(y * w); q.add(y * w + w - 1) }
            while (q.isNotEmpty()) {
                val i = q.poll()!!
                if (seen[i] || !bg(px[i])) continue
                seen[i] = true; px[i] = 0
                val x = i % w; val y = i / w
                if (x > 0) q.add(i - 1); if (x < w - 1) q.add(i + 1); if (y > 0) q.add(i - w); if (y < h - 1) q.add(i + w)
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        }
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h step 2) for (x in 0 until w step 2) if ((px[y * w + x] ushr 24) > 24) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        val rect = if (r < 0) android.graphics.Rect(0, 0, w, h) else android.graphics.Rect(l, t, minOf(w, r + 2), minOf(h, b + 2))
        return bmp to rect
    }

    suspend fun load(context: Context, url: String): ImageBitmap? = memory[url] ?: withContext(Dispatchers.IO) {
        runCatching {
            val f = cacheFile(context, url)
            if (!f.exists() || f.length() == 0L) {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    val bytes = r.body?.bytes() ?: return@runCatching null
                    f.writeBytes(bytes)
                }
            }
            val (bmp, rect) = process(BitmapFactory.decodeFile(f.path) ?: return@runCatching null)
            bounds[url] = rect
            bmp.asImageBitmap()
        }.getOrNull()?.also { memory[url] = it }
    }
}

/**
 * 실제 차량 보기. interactive=true 면 좌우 드래그로 360° 회전(spin) 또는 각도 넘기기(gallery).
 * frameOverride 를 주면 그 각도로 고정(쇼룸 턴테이블 등에서 사용, 0..1 = 한 바퀴).
 */
@Composable
fun OfficialCarView(
    id: String, style: CarStyle, modifier: Modifier = Modifier, interactive: Boolean = false,
    silhouette: Boolean = false, turn: Float? = null, showHint: Boolean = interactive,
) {
    val ctx = LocalContext.current
    val views = remember(id) { OfficialCarImages.views(ctx, id) }
    val count = when (views) { is CarViews.Spin -> views.frames; is CarViews.Gallery -> views.urls.size; null -> 0 }
    val start = (views as? CarViews.Spin)?.start ?: 0
    var index by remember(id) { mutableIntStateOf(start) }
    val shown = if (turn != null && count > 0) (start + (turn * count).roundToInt()) % count else index
    val loaded = remember(id) { mutableStateMapOf<Int, ImageBitmap>() }
    fun urlOf(i: Int): String? = when (views) {
        is CarViews.Spin -> views.frameUrl(i)
        is CarViews.Gallery -> views.urls.getOrNull(((i % count) + count) % count)
        null -> null
    }

    // 현재 각도 먼저, 돌려 보기를 켜면 나머지 프레임도 미리 받아 부드럽게
    LaunchedEffect(id, interactive || turn != null) {
        if (views == null) return@LaunchedEffect
        val order = buildList {
            add(shown)
            if (interactive || turn != null) for (d in 1..count / 2) { add(shown + d); add(shown - d) }
        }.map { ((it % count) + count) % count }.distinct()
        for (i in order) {
            if (i in loaded) continue
            val u = urlOf(i) ?: continue
            OfficialCarImages.load(ctx, u)?.let { loaded[i] = it }
        }
    }
    // 아직 못 받은 각도면 가장 가까운 받은 각도를 보여준다
    val bmp = loaded[shown] ?: loaded.keys.minByOrNull { k -> minOf(abs(k - shown), count - abs(k - shown)) }?.let { loaded[it] }

    val drag = if (interactive && count > 1) Modifier.pointerInput(id) {
        var acc = 0f
        detectHorizontalDragGestures { change, dx ->
            change.consume()
            acc += dx
            val step = if (views is CarViews.Spin) size.width / (count * 0.9f) else size.width / 4f
            while (acc <= -step) { acc += step; index = (index + 1) % count }
            while (acc >= step) { acc -= step; index = (index - 1 + count) % count }
        }
    } else Modifier

    Box(modifier.then(drag), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            // 여백을 잘라 차가 꽉 차게: 360°는 시작(앞 3/4) 프레임 기준 영역을 넓혀 모든 각도에 공통 적용
            val crop = remember(bmp, views) {
                val base = when (views) {
                    is CarViews.Spin -> OfficialCarImages.bounds(views.frameUrl(views.start))
                    else -> urlOf(shown)?.let { OfficialCarImages.bounds(it) }
                } ?: android.graphics.Rect(0, 0, bmp.width, bmp.height)
                val padX = if (views is CarViews.Spin) base.width() * 0.13f else base.width() * 0.04f
                val padY = base.height() * (if (views is CarViews.Spin) 0.12f else 0.04f)
                android.graphics.RectF(
                    maxOf(0f, base.left - padX), maxOf(0f, base.top - padY),
                    minOf(bmp.width.toFloat(), base.right + padX), minOf(bmp.height.toFloat(), base.bottom + padY),
                )
            }
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val sc = minOf(size.width / crop.width(), size.height / crop.height())
                val dw = crop.width() * sc; val dh = crop.height() * sc
                drawImage(
                    bmp, srcOffset = androidx.compose.ui.unit.IntOffset(crop.left.toInt(), crop.top.toInt()),
                    srcSize = androidx.compose.ui.unit.IntSize(crop.width().toInt(), crop.height().toInt()),
                    dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                    colorFilter = if (silhouette) ColorFilter.tint(Color(0xFF2E3644)) else null,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                )
            }
        } else {
            // 로딩 전·오프라인: 실제 제원 기반 3D 모델
            Canvas(Modifier.fillMaxSize()) {
                val h = REAL_DIMS[id]?.let { it.h.toFloat() / it.l } ?: 0.33f
                val w = minOf(size.width * 0.74f, size.height * 0.62f / (h + 0.18f))
                drawCar3D(id, style, size.width / 2, size.height * 0.84f, w, yawDeg = 35f, silhouette = silhouette)
            }
        }
        if (showHint && count > 1 && !silhouette) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (views is CarViews.Spin) "↔ 드래그해서 360° 돌려보기" else "↔ 각도 넘기기 ${shown + 1}/$count", color = Color.White, fontSize = 12.sp)
            }
        }
        if (bmp != null && !silhouette) {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                Text("이미지: 제조사 공식", color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
            }
        }
    }
}
