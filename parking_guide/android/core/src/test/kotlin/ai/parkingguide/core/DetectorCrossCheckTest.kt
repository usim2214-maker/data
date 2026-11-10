package ai.parkingguide.core

import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Python 프로토타입이 만든 합성 후방카메라 영상(640×360)에서
 * Kotlin 인식기가 Python 인식기와 같은 결과를 내는지, 정답과도 가까운지 확인한다.
 */
class DetectorCrossCheckTest {
    data class Fixture(val file: String, val camera: String, val gt: DoubleArray, val py: DoubleArray)

    private val dir = File(javaClass.getResource("/fixtures/fixtures.tsv")!!.toURI()).parentFile
    private val fixtures = File(dir, "fixtures.tsv").readLines().filter { !it.startsWith("#") && it.isNotBlank() }.map {
        val p = it.split("\t")
        Fixture(p[0], p[1], doubleArrayOf(p[2].toDouble(), p[3].toDouble(), p[4].toDouble()),
            doubleArrayOf(p[5].toDouble(), p[6].toDouble(), p[7].toDouble(), p[8].toDouble()))
    }
    private val cams = mapOf(
        "wide" to CameraModel.wideAngle(640, 360),
        "pinhole" to CameraModel(640, 360, focalPx = 280.0),
    )

    @BeforeTest fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

    @Test fun matchesPythonAndGroundTruth() {
        val dets = cams.mapValues { SlotDetector(it.value) }
        val report = StringBuilder()
        for (f in fixtures) {
            val img = Imgcodecs.imread(File(dir, f.file).path)
            val res = dets.getValue(f.camera).analyze(img)
            val slot = res.slot
            if (f.py[0].isNaN()) { assertNull(slot, "${f.file}: Python은 못 찾았는데 Kotlin은 찾음"); continue }
            assertNotNull(slot, "${f.file}: Kotlin이 주차칸을 못 찾음")
            val a = computeAlignment(slot, VehicleSpec.DEFAULT)
            report.appendLine("${f.file} gt=(${f.gt[0]},${f.gt[1]},${f.gt[2]}) py=(${f.py[0]},${f.py[1]},${f.py[2]},${f.py[3]}) " +
                "kt=(${"%.2f".format(a.offsetCm)},${"%.2f".format(a.yawDeg)},${a.endDistCm?.let { "%.1f".format(it) }},${"%.2f".format(slot.confidence)})")
            // Kotlin ≈ Python
            assertTrue(abs(a.offsetCm - f.py[0]) < 1.5, "${f.file} offset kt=${a.offsetCm} py=${f.py[0]}")
            assertTrue(abs(a.yawDeg - f.py[1]) < 0.3, "${f.file} yaw kt=${a.yawDeg} py=${f.py[1]}")
            assertTrue(abs(slot.confidence - f.py[3]) < 0.15, "${f.file} conf kt=${slot.confidence} py=${f.py[3]}")
            if (!f.py[2].isNaN()) assertTrue(abs((a.endDistCm ?: 1e9) - f.py[2]) < 5, "${f.file} end kt=${a.endDistCm} py=${f.py[2]}")
            // 신뢰도가 충분한 경우 정답과도 가깝다
            if (slot.confidence >= LOW_CONFIDENCE) {
                assertTrue(abs(a.offsetCm - f.gt[0]) < 4.5, "${f.file} offset vs gt")
                assertTrue(abs(a.yawDeg - f.gt[1]) < 1.0, "${f.file} yaw vs gt")
            }
        }
        println(report)
    }
}
