package com.signalassist.demo

import com.signalassist.core.SignalState
import com.signalassist.core.TraceParser
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot

/** 3D 렌더러 미리보기: ./gradlew :demo:preview3d --args="출력폴더" */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/preview3d").apply { mkdirs() }
    val dir = listOf(File("../sim/data"), File("sim/data")).first { File(it, "centum_map.txt").exists() }
    val city = City3D(File(dir, "centum_map.txt"))
    println("세운 건물 ${city.buildings.size}개")
    val steps = TraceParser.parse(File(dir, "centum_trace.csv").readLines())
    for (i in listOf(100, 300, 1150, 1700)) {
        val s = steps[i]
        val img = BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB)
        val cam = City3D.Camera(s.x.toDouble(), s.y.toDouble(), 1.3, Math.toRadians(s.angleDeg.toDouble()), 0.0, 960, 540, 960 * 0.866, 480.0, 540 * 0.55)
        val items = ArrayList<City3D.Item>()
        s.tlsDistM?.takeIf { it <= 150 }?.let { items += City3D.Light(it + 12.0, 1.0, 4.0, SignalState(s.signalColor)) }
        for ((ox, oy, a) in s.others) {
            val d = hypot(ox - s.x, oy - s.y).toDouble()
            if (d < 220) items += City3D.Car(ox, oy, a, Color(150, 155, 165), false, d)
        }
        val t0 = System.nanoTime()
        city.render(img, cam, items)
        println("frame $i: %.1f ms".format((System.nanoTime() - t0) / 1e6))
        ImageIO.write(img, "png", File(out, "drive_$i.png"))
        val det = com.signalassist.core.TrafficLightDetector().detect(City3D.toFrame(img))
        println("  실제 신호 ${s.signalColor} (정지선 ${s.tlsDistM}m) -> 인식 ${det.color}, 등화 ${det.lamps.size}개 ${det.lamps.take(3).map { "${it.color}/${"%.2f".format(it.score)}@${it.centerY.toInt()}" }}")
    }
    val img = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
    val s = steps[600]
    val cam = City3D.Camera(s.x - 420.0, s.y + 380.0, 300.0, Math.toRadians(135.0), -0.55, 1280, 720, 1280 * 0.75, 640.0, 360.0)
    city.render(img, cam, emptyList(), drawRoute = true, viewRange = 1600.0)
    ImageIO.write(img, "png", File(out, "aerial.png"))
}
