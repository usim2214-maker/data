package com.signalassist.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.signalassist.core.DriverNeed
import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingAssistEngine
import com.signalassist.core.EngineOutput
import com.signalassist.core.SampleSource
import com.signalassist.core.Scenario
import com.signalassist.core.ScenarioPlayer
import com.signalassist.core.SignalQuery
import com.signalassist.core.TraceParser
import com.signalassist.core.TracePlayer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * 시연 영상용 '실제 앱 화면 녹화'. 에뮬레이터 없이 실제 MainActivity 에 엔진 결과를 0.1초마다 흘려 넣고
 * 주행 화면을 PNG 로 저장한다. 평소 테스트에서는 건너뛴다.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*DemoRecordingTest*' -Precord=true
 *   -> app/build/recording/<장면>/f_00000.png ..., deliveries.tsv
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoRecordingTest {
    private val record = System.getProperty("record") == "true"

    @After fun clear() {
        AssistBus.config.value = null
        AssistBus.snapshot.value = null
    }

    private fun shot(activity: MainActivity, file: File, w: Int = 1024, h: Int = 600) {
        val v = activity.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(img))
        file.outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 90, it) }
        img.recycle()
    }

    private fun recordRun(name: String, source: SampleSource, profile: DriverProfile, queries: Set<Int>, fullScreenFlash: Boolean = false) {
        val out = File("build/recording/$name").apply { deleteRecursively(); mkdirs() }
        val settings = Settings(RuntimeEnvironment.getApplication())
        settings.onboarded = true
        settings.mode = AssistMode.Simulated(name)
        settings.profile = profile
        settings.fullScreenFlash = fullScreenFlash
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        val engine = DrivingAssistEngine(profile)
        var lastSignal: EngineOutput? = null
        val log = StringBuilder("index\tt_ms\ttype\tlevel\ttone\tmessage\tspeech\theadline\tsymbol\tplan_tone\tplan_voice\tplan_flash\tplan_silent\n")
        for (i in 0 until source.frameCount) {
            val sample = source.sample(i)
            val o = engine.process(sample.toInput())
            o.signal?.let { lastSignal = it.copy(timestampMs = sample.timestampMs) }
            AssistBus.snapshot.value = UiSnapshot(o.copy(input = o.input.copy(frame = null)), null, "체험 입력", true)
            val deliveries = o.deliveries.toMutableList()
            if (i in queries) {
                val a = SignalQuery.answer(lastSignal, sample.timestampMs)
                deliveries += com.signalassist.core.Delivery(a, SignalQuery.plan(profile, a))
            }
            for (d in deliveries) {
                AssistBus.alerts.tryEmit(d.alert to d.plan)
                val a = d.alert; val p = d.plan
                log.append("$i\t${sample.timestampMs}\t${a.type}\t${a.level}\t${a.tone}\t${a.message}\t${a.speech}\t${a.headline}\t${a.symbol}\t${p.tone}\t${p.voice}\t${p.visualFlash}\t${p.silent}\n")
            }
            looper.idleFor(Duration.ofMillis(100))
            shot(activity, File(out, "f_%05d.png".format(i)))
        }
        File(out, "deliveries.tsv").writeText(log.toString())
        controller.pause().stop().destroy()
        println("RECORDED $name ${source.frameCount} frames")
    }

    @Test fun recordStopAndGo() {
        assumeTrue(record)
        recordRun("stop_and_go", ScenarioPlayer(Scenario.STOP_AND_GO), DriverProfile(setOf(DriverNeed.COLOR_VISION)), setOf(90))
    }

    @Test fun recordHearing() {
        assumeTrue(record)
        recordRun("hearing_drive", ScenarioPlayer(Scenario.HEARING_DRIVE), DriverProfile(setOf(DriverNeed.HEARING)), emptySet(), fullScreenFlash = true)
    }

    @Test fun recordCentum() {
        assumeTrue(record)
        val trace = listOf(File("../sim/data/centum_trace.csv"), File("sim/data/centum_trace.csv")).first { it.exists() }
        recordRun("centum", TracePlayer(TraceParser.parse(trace.readLines())), DriverProfile(setOf(DriverNeed.COLOR_VISION)), setOf(350))
    }

    /** 설정·체험·첫 설정 화면 (정지 화면) */
    @Test fun recordStaticScreens() {
        assumeTrue(record)
        val out = File("build/recording/static").apply { deleteRecursively(); mkdirs() }
        val settings = Settings(RuntimeEnvironment.getApplication())
        settings.onboarded = true
        settings.mode = AssistMode.LiveNoCamera
        settings.profile = DriverProfile(setOf(DriverNeed.COLOR_VISION, DriverNeed.HEARING))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        AssistBus.snapshot.value = UiSnapshot(com.signalassist.core.DrivingOutput(com.signalassist.core.DrivingInput(1_000,
            com.signalassist.core.VehicleState(Float.NaN, speedStatus = com.signalassist.core.InputStatus.UNAVAILABLE)), null, emptyList()), null, "", false)
        looper.idleFor(Duration.ofMillis(200))
        shot(activity, File(out, "01-driving-unconnected.png"))
        fun clickTab(text: String) {
            fun all(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
            all(activity.window.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == text }.performClick()
            looper.idleFor(Duration.ofMillis(200))
        }
        clickTab("설정"); shot(activity, File(out, "02-settings.png"))
        clickTab("체험"); shot(activity, File(out, "03-demo.png"))
        controller.pause().stop().destroy()
        println("RECORDED static")
    }
}
