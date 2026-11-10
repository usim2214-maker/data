package ai.parkingguide.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 후방카메라 영상 입력.
 *
 * 2026-11 기준 Pleos Vehicle SDK에는 후방카메라 영상 API가 없고, 하드웨어 직접 접근을 금지하는 정책이 있다.
 * 그래서 지금은 두 가지 입력을 둔다.
 *  - [AssetReplayFrameSource]: 에뮬레이터 시연용. Python 합성기로 만든 프레임(assets/demo 폴더의 PNG)을 재생
 *  - [Camera2FrameSource]: 시험대(USB/외부 카메라)용
 * Pleos 측에서 카메라 접근이 허용되면 같은 인터페이스로 구현을 하나 더 추가하면 된다.
 */
interface FrameSource {
    val width: Int
    val height: Int
    /** frame: 인식용 Mat(그레이 또는 RGBA), display: 화면 표시용 비트맵, tNanos: 시각 */
    fun start(onFrame: (frame: Mat, display: Bitmap, tNanos: Long) -> Unit)
    fun stop()
}

class AssetReplayFrameSource(private val context: Context, private val dir: String = "demo", private val fps: Double = 10.0) : FrameSource {
    private val files = (context.assets.list(dir) ?: emptyArray()).filter { it.endsWith(".png") }.sorted()
    private val first: Bitmap? = files.firstOrNull()?.let { decode(it) }
    override val width = first?.width ?: 640
    override val height = first?.height ?: 360
    val available get() = files.isNotEmpty()

    private val thread = HandlerThread("replay").apply { start() }
    private val handler = Handler(thread.looper)
    private var index = 0
    private var running = false

    private fun decode(name: String) = context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) }

    override fun start(onFrame: (Mat, Bitmap, Long) -> Unit) {
        if (files.isEmpty()) return
        running = true
        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val bmp = decode(files[index]).copy(Bitmap.Config.ARGB_8888, false)
                val mat = Mat()
                Utils.bitmapToMat(bmp, mat) // RGBA
                onFrame(mat, bmp, System.nanoTime())
                index = (index + 1) % files.size
                handler.postDelayed(this, (1000 / fps).toLong())
            }
        })
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}

/** Camera2 입력 (뒤쪽/외부 카메라). 인식에는 밝기(Y) 채널만 쓴다. */
class Camera2FrameSource(context: Context, override val width: Int = 640, override val height: Int = 360) : FrameSource {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null

    val cameraId: String? = manager.cameraIdList.firstOrNull {
        val facing = manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
        facing == CameraCharacteristics.LENS_FACING_EXTERNAL || facing == CameraCharacteristics.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull()

    @SuppressLint("MissingPermission") // MainActivity가 CAMERA 권한을 확인한 뒤 호출한다
    override fun start(onFrame: (Mat, Bitmap, Long) -> Unit) {
        val id = cameraId ?: return
        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            val plane = img.planes[0]
            val gray = Mat(img.height, img.width, CvType.CV_8UC1)
            val row = ByteArray(img.width)
            val buf = plane.buffer
            for (y in 0 until img.height) {
                buf.position(y * plane.rowStride)
                buf.get(row, 0, img.width)
                gray.put(y, 0, row)
            }
            val t = img.timestamp
            img.close()
            val rgba = Mat()
            Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
            val bmp = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            rgba.release()
            onFrame(gray, bmp, t)
        }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) = s.setRepeatingRequest(req.build(), null, handler).let { }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, handler)
            }

            override fun onDisconnected(cam: CameraDevice) = cam.close()
            override fun onError(cam: CameraDevice, error: Int) = cam.close()
        }, handler)
    }

    override fun stop() {
        device?.close()
        reader?.close()
        thread.quitSafely()
    }
}
