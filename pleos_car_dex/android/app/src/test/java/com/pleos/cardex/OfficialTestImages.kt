package com.pleos.cardex

import android.content.Context
import com.pleos.cardex.ui.components.CarViews
import com.pleos.cardex.ui.components.OfficialCarImages
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 스크린샷 테스트용: 공식 이미지를 미리 받아 앱 캐시에 넣는다 (네트워크가 없으면 3D 대체 화면으로 찍힘) */
object OfficialTestImages {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun urlsFor(context: Context, id: String, allFrames: Boolean = false): List<String> = when (val v = OfficialCarImages.views(context, id)) {
        is CarViews.Spin -> if (allFrames) (0 until v.frames).map { v.frameUrl(it) } else listOf(v.frameUrl(v.start))
        is CarViews.Gallery -> v.urls.take(if (allFrames) v.urls.size else 1)
        null -> emptyList()
    }

    fun prime(context: Context, urls: List<String>) = urls.forEach { u ->
        val f = OfficialCarImages.cacheFile(context, u)
        if (f.exists() && f.length() > 0) return@forEach
        runCatching { client.newCall(Request.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes()?.let { f.writeBytes(it) } } }
    }
}
