package com.signalassist.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.signalassist.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    @After fun clear() {
        AssistBus.config.value = null
        AssistBus.snapshot.value = null
        AssistBus.speedOverrideKmh = null
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun click(activity: MainActivity, text: String) {
        val button = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == text }
        assertTrue(button.isShown)
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun screenshot(activity: MainActivity, name: String, width: Int = 1024, height: Int = 600) {
        val view = activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val file = File("build/review/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun screensExposeRealCapabilitiesAndSeparateSettings() {
        val app = RuntimeEnvironment.getApplication()
        val settings = Settings(app)
        settings.onboarded = true
        settings.mode = AssistMode.LiveNoCamera
        settings.profile = DriverProfile(setOf(DriverNeed.HEARING))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val snapshot = UiSnapshot(DrivingOutput(DrivingInput(1_000,
            VehicleState(Float.NaN, speedStatus = InputStatus.UNAVAILABLE)), null, emptyList()), null, "", false)
        activity.javaClass.getDeclaredMethod("render", UiSnapshot::class.java).apply { isAccessible = true }.invoke(activity, snapshot)
        screenshot(activity, "01-driving")
        val visible = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }
        assertTrue(visible.any { it.text.contains("차량 연결을 확인") })
        assertTrue(visible.any { it.text.contains("내비게이션 미연결") })
        assertTrue(visible.any { it.text == "알림 다시 보기" })
        assertFalse(visible.any { it.text == "글씨 더 크게" })
        click(activity, "설정")
        screenshot(activity, "02-settings")
        click(activity, "체험")
        screenshot(activity, "03-demo")
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
            it.isShown && it.text.contains("가상 신호와 시나리오")
        })
        settings.mode = AssistMode.Simulated(Scenario.STOP_AND_GO.name)
        settings.profile = DriverProfile.COLOR_VISION_ONLY
        activity.javaClass.getDeclaredMethod("publishConfig").apply { isAccessible = true }.invoke(activity)
        click(activity, "주행")
        val signal = EngineOutput(1_000, FrameDetection(SignalColor.RED, false, emptyList()),
            SignalState(SignalColor.RED), null, VehicleState(0f), emptyList(), confidence = 1f)
        val demo = snapshot.copy(output = DrivingOutput(DrivingInput(1_000, VehicleState(0f)), signal, emptyList()))
        activity.javaClass.getDeclaredMethod("render", UiSnapshot::class.java).apply { isAccessible = true }.invoke(activity, demo)
        val alert = Alert(AlertType.RED_STOP, 1_000)
        activity.javaClass.getDeclaredMethod("showAlert", Alert::class.java, OutputPlan::class.java)
            .apply { isAccessible = true }.invoke(activity, alert, OutputPlan(false, false, false, false))
        screenshot(activity, "05-demo-signal")
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
            it.isShown && it.text.contains("체험 모드")
        })
        controller.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "w640dp-h480dp-land-mdpi")
    fun largeTextAndDrivingRestrictionKeepSettingsOutOfDriving() {
        val settings = Settings(RuntimeEnvironment.getApplication())
        settings.onboarded = true
        settings.mode = AssistMode.LiveNoCamera
        settings.largeText = true
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "설정")
        activity.javaClass.getDeclaredField("platformRestricted").apply { isAccessible = true }.set(activity, true)
        activity.javaClass.getDeclaredMethod("updateRestrictions").apply { isAccessible = true }.invoke(activity)
        screenshot(activity, "04-driving-restricted", 640, 480)
        assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>().any {
            it.isShown && it.text == "설정"
        })
        controller.pause().stop().destroy()
    }
}
