package com.pleos.cardex.pleos

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** 백엔드로 보낼 카메라/영상 프레임 한 장 */
class Frame(val jpeg: ByteArray, val name: String)

interface FrameSource {
    /** 다음 프레임. 없으면 null */
    suspend fun next(): Frame?
    fun release() {}
}

/**
 * 주행 영상 직접 입력 (현재 MVP 방식).
 * 화면에서 재생 중인 영상의 현재 위치(positionMs) 프레임을 잘라 JPEG 로 만든다.
 *
 * TODO(Pleos): 서라운드뷰 등 차량 내부 영상 API 가 연계되면 이 클래스만 교체.
 */
class VideoFrameSource(
    context: Context,
    val uri: Uri,
    private val positionMs: () -> Long,
) : FrameSource {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

    override suspend fun next(): Frame? {
        val ms = positionMs()
        val bmp = retriever.getFrameAtTime(ms * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return Frame(out.toByteArray(), "drive-${ms}ms.jpg")
    }

    override fun release() = retriever.release()

    companion object {
        /** assets/demo_video/demo_drive.mp4 를 캐시로 복사해 Uri 반환 (VideoView/Retriever 공용). 없으면 null */
        fun demoVideoUri(context: Context, assetPath: String = "demo_video/demo_drive.mp4"): Uri? = runCatching {
            val file = java.io.File(context.cacheDir, assetPath.substringAfterLast('/'))
            if (!file.exists()) context.assets.open(assetPath).use { input -> file.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(file)
        }.getOrNull()
    }
}

/** 대체 입력: assets/demo_frames/ 의 이미지를 순서대로 반환 */
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
