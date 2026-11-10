package com.signalassist.app.camera

import android.content.Context
import android.graphics.BitmapFactory
import com.signalassist.core.Frame

/**
 * assets/test_frames/ 의 이미지(블랙박스 캡처 등)를 일정 간격으로 순환 재생한다.
 */
class AssetImageFrameSource(
    private val context: Context,
    private val holdMs: Long = 2_000,
    private val dir: String = "test_frames",
) : FrameSource {
    private val files: List<String> = (context.assets.list(dir) ?: emptyArray())
        .filter { it.lowercase().endsWith(".png") || it.lowercase().endsWith(".jpg") || it.lowercase().endsWith(".jpeg") }
        .sorted()

    override val name = "테스트 이미지 ${files.size}장"
    private var startMs: Long? = null
    private var loadedIndex = -1
    private var loaded: Frame? = null

    val isEmpty get() = files.isEmpty()

    override fun nextFrame(nowMs: Long): Frame? {
        if (files.isEmpty()) return null
        val start = startMs ?: nowMs.also { startMs = it }
        val index = (((nowMs - start) / holdMs) % files.size).toInt()
        if (index != loadedIndex) {
            loaded = context.assets.open("$dir/${files[index]}").use { BitmapFactory.decodeStream(it) }?.toFrame()
            loadedIndex = index
        }
        return loaded
    }
}
