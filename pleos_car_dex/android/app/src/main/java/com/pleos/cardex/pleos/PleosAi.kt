package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.OnServerConnectionListener
import ai.pleos.playground.llm.listener.ResultListener
import com.pleos.cardex.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Pleos LLM SDK (Pleos Playground 공개 API) 래퍼.
 * - AI 코스: 서버가 준 후보·프롬프트로 코스를 고르게 함 (시간·점수는 서버가 다시 계산)
 * 참고: LLM 2.1.3.2 공개 API 는 텍스트 입력(generateContent)만 열려 있고 이미지 입력(ImagePart)은 SDK 내부용이라
 *       차종 판별에는 쓰지 않는다 (서버 판별 → 추후 YOLO 추가 학습).
 * Playground 앱 ID/키가 있으면 SERVER 모드(registerApp), 없으면 ON_DEVICE 모드.
 * 사용할 수 없으면 available=false → 앱은 서버 판별/규칙 기반으로 대체.
 */
class PleosAi(context: Context) {
    private val hasKey = BuildConfig.PLEOS_APP_ID.isNotBlank() && BuildConfig.PLEOS_APP_KEY.isNotBlank()
    private val llm: LLM? = runCatching { LLM(context.applicationContext, if (hasKey) Mode.SERVER else Mode.ON_DEVICE) }
        .onFailure { Log.w(TAG, "Pleos LLM unavailable", it) }.getOrNull()

    @Volatile var available = false
        private set
    val modeLabel: String get() = when { !available -> "사용 안 함"; hasKey -> "Pleos LLM (서버)"; else -> "Pleos LLM (기기)" }

    init {
        llm?.let { l ->
            runCatching {
                l.initialize()
                if (hasKey) {
                    l.registerApp(BuildConfig.PLEOS_APP_ID, BuildConfig.PLEOS_APP_KEY, object : OnServerConnectionListener {
                        override fun onConnected() { available = true }
                        override fun onFailed(message: String) { Log.w(TAG, "registerApp failed: $message") }
                    })
                } else {
                    available = true
                }
            }.onFailure { Log.w(TAG, "Pleos LLM init failed", it) }
        }
    }

    /** 서버가 준 코스 프롬프트 → {title, summary, stop_ids, reasons} */
    suspend fun pickCourse(prompt: String): CoursePick? {
        if (!available) return null
        val answer = generate(prompt) ?: return null
        return runCatching {
            val json = answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1)
            val o = Json.parseToJsonElement(json) as JsonObject
            CoursePick(
                title = o["title"]!!.jsonPrimitive.content,
                summary = o["summary"]?.jsonPrimitive?.content.orEmpty(),
                stopIds = o["stop_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
                reasons = o["reasons"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }.onFailure { Log.w(TAG, "course parse failed: $answer", it) }.getOrNull()
    }

    private suspend fun generate(prompt: String): String? = withTimeoutOrNull(TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            runCatching {
                llm!!.generateContent(prompt, object : ResultListener {
                    override fun onResponse(text: String, done: Boolean) {
                        sb.append(text)
                        if (done && cont.isActive) cont.resume(sb.toString())
                    }
                    override fun onError(message: String) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    fun release() { runCatching { llm?.release() } }

    data class CoursePick(val title: String, val summary: String, val stopIds: List<String>, val reasons: List<String>)

    private companion object {
        const val TAG = "PleosAi"
        const val TIMEOUT_MS = 20_000L
    }
}
