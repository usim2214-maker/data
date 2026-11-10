package com.signalassist.app.camera

import android.graphics.Bitmap
import com.signalassist.core.Frame

/**
 * 영상 공급원.
 * Pleos Playground 공개 API 는 카메라를 제공하지 않으므로 지금은 합성/테스트 이미지 구현만 있다.
 * 향후 카메라 접근이 열리면(또는 외장 카메라를 쓰면) CameraX ImageAnalysis 결과를
 * [Frame] 으로 변환하는 구현체를 추가하면 된다.
 */
interface FrameSource {
    val name: String
    fun start() {}
    /** 현재 시각의 프레임. 없으면 null */
    fun nextFrame(nowMs: Long): Frame?
    fun stop() {}
}

fun Bitmap.toFrame(maxWidth: Int = 960): Frame {
    val src = if (width > maxWidth) {
        Bitmap.createScaledBitmap(this, maxWidth, (height * maxWidth.toFloat() / width).toInt(), true)
    } else {
        this
    }
    val px = IntArray(src.width * src.height)
    src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
    return Frame(src.width, src.height, px)
}

fun Frame.toBitmap(): Bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
