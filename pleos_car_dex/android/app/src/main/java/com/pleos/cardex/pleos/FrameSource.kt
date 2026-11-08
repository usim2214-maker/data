package com.pleos.cardex.pleos

import android.content.Context

/** 백엔드로 보낼 카메라 프레임 한 장 */
class Frame(val jpeg: ByteArray, val name: String)

interface FrameSource {
    /** 다음 프레임. 없으면 null */
    suspend fun next(): Frame?
}

/**
 * 시연용: assets/demo_frames/ 의 이미지를 순서대로 반환.
 * Pleos 에뮬레이터에는 실제 차량 카메라 스트림이 없으므로 MVP 시연은 이 소스를 사용한다.
 *
 * TODO(Pleos): 실차/카메라 지원 환경에서는 CameraX(또는 Pleos 카메라 API)로
 *  일정 주기(예: 1fps) 프레임을 JPEG 로 인코딩하는 CameraFrameSource 를 추가.
 */
class AssetFrameSource(private val context: Context, private val dir: String = "demo_frames") : FrameSource {
    private val files: List<String> by lazy {
        context.assets.list(dir).orEmpty()
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) }
            .sorted()
    }
    private var index = 0

    override suspend fun next(): Frame? {
        if (files.isEmpty()) return null
        val name = files[index++ % files.size]
        val bytes = context.assets.open("$dir/$name").use { it.readBytes() }
        return Frame(bytes, name)
    }
}
