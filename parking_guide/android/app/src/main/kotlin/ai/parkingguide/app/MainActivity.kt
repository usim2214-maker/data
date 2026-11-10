package ai.parkingguide.app

import ai.parkingguide.core.CameraModel
import ai.parkingguide.core.DoorMode
import ai.parkingguide.core.ParkingAssistant
import ai.parkingguide.core.VehicleSpec
import ai.parkingguide.core.municipalPayload
import android.Manifest
import android.app.Activity
import android.car.Car
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 후방 주차 가이드 메인 화면.
 * R단에 들어가면 안내를 시작하고, R단에서 나오면 리포트 카드를 보여 준다.
 * 기어 신호가 없는 환경(에뮬레이터 등)에서는 항상 후진 중으로 보고 동작한다.
 */
class MainActivity : Activity() {
    private lateinit var guide: GuideView
    private lateinit var reportView: ReportView
    private lateinit var signals: VehicleSignals
    private lateinit var voice: VoiceOutput
    private lateinit var location: LocationSource
    private lateinit var history: HistoryStore
    private var frames: FrameSource? = null
    private var assistant: ParkingAssistant? = null

    // 차종·하차 모드는 설정 화면에서 고르도록 확장할 수 있다 (CarInfo는 VIN만 제공)
    private val vehicle = VehicleSpec.DEFAULT
    private val doorMode = DoorMode.CENTER

    private val worker = HandlerThread("parking-guide").apply { start() }
    private val work = Handler(worker.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var reversing = true
    private var lastT: Long? = null
    private var t0: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV load failed")
        guide = GuideView(this)
        reportView = ReportView(this).apply {
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        setContentView(FrameLayout(this).apply { addView(guide); addView(reportView) })
        voice = VoiceOutput(this)
        location = LocationSource(this)
        history = HistoryStore(this)
        signals = VehicleSignals(this) { rev -> ui.post { onReverseChanged(rev) } }

        val need = listOf(Car.PERMISSION_SPEED, Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startAll() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        startAll()
    }

    private fun startAll() {
        signals.start(canReadSpeed = checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED)
        location.refresh()
        val replay = AssetReplayFrameSource(this)
        val source: FrameSource = when {
            replay.available -> replay // 에뮬레이터 시연: tools/export_demo_frames.py 로 만든 프레임
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> Camera2FrameSource(this)
            else -> replay
        }
        frames = source
        // 카메라 보정값: 실제 차량에서는 체커보드 보정으로 바꾼다
        val cam = CameraModel.wideAngle(source.width, source.height)
        guide.camera = cam
        guide.vehicle = vehicle
        assistant = ParkingAssistant(cam, vehicle, doorMode)
        source.start { mat, bmp, t -> onFrame(mat, bmp, t) }
    }

    private fun onFrame(mat: Mat, bmp: Bitmap, tNanos: Long) {
        if (!reversing || !busy.compareAndSet(false, true)) { mat.release(); return } // 밀리면 프레임을 건너뛴다
        work.post {
            try {
                val a = assistant ?: return@post
                val dt = lastT?.let { (tNanos - it) / 1e9 } ?: 0.0
                lastT = tNanos
                val start = t0 ?: tNanos.also { t0 = it }
                // 이동 거리 = 차속 × 시간 (차속 신호가 없으면 0 → 추적기는 검출값만으로 갱신)
                val ds = signals.speedMps * dt * 100.0
                val steer = signals.frontWheelDeg?.let { vehicle.steeringWheelFromFrontWheel(it) }
                val out = a.step(mat, steer, ds, (tNanos - start) / 1e9)
                ui.post {
                    guide.update(bmp, out.slot, out.alignment, out.guidance, steer)
                    voice.say(out.speech)
                    voice.setBeepInterval(out.beepIntervalS)
                }
            } finally {
                mat.release()
                busy.set(false)
            }
        }
    }

    private fun onReverseChanged(rev: Boolean) {
        if (rev == reversing) return
        reversing = rev
        if (rev) {
            work.post { assistant?.reset(); lastT = null; t0 = null }
            reportView.visibility = View.GONE
        } else {
            voice.setBeepInterval(null)
            work.post {
                val report = assistant?.finish() ?: return@post
                val withTrend = history.add(report)
                // 지자체 공유 데이터는 운전자 동의가 있을 때만 전송한다 (여기서는 기기 로그에만 남김)
                val loc = location.last
                Log.i(TAG, "municipal payload: " + municipalPayload(withTrend, loc?.latitude, loc?.longitude, loc?.heading?.toDouble()))
                ui.post { reportView.report = withTrend; reportView.visibility = View.VISIBLE }
            }
        }
    }

    override fun onDestroy() {
        frames?.stop()
        signals.stop()
        voice.release()
        location.release()
        worker.quitSafely()
        super.onDestroy()
    }

    private companion object { const val TAG = "ParkingGuide" }
}
