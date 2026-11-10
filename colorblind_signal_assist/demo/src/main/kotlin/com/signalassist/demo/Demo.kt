package com.signalassist.demo

import com.signalassist.core.Frame
import com.signalassist.core.Lighting
import com.signalassist.core.SceneSpec
import com.signalassist.core.ScenarioPlayer
import com.signalassist.core.Scenario
import com.signalassist.core.SignalAssistEngine
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import com.signalassist.core.SyntheticSceneGenerator
import com.signalassist.core.TrafficLightDetector
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * 사용법:
 *   ./gradlew :demo:run                      # 모든 시나리오 실행 + 정확도 평가
 *   ./gradlew :demo:run --args="out_dir"     # 합성 프레임 PNG 저장 위치 지정
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/demo-frames").apply { mkdirs() }

    for (scenario in Scenario.ALL) runScenario(scenario, outDir)
    evaluateDetector()
    println("\n샘플 프레임 저장 위치: ${outDir.absolutePath}")
}

private fun runScenario(scenario: Scenario, outDir: File) {
    println("\n=== 시나리오: ${scenario.name} — ${scenario.description}")
    val player = ScenarioPlayer(scenario)
    val engine = SignalAssistEngine()
    var correct = 0
    var known = 0
    for (sample in player.samples()) {
        val out = engine.process(sample.frame, sample.vehicle, sample.timestampMs)
        if (out.state.color != SignalColor.UNKNOWN) {
            known++
            if (out.state.color == sample.groundTruth.color) correct++
        }
        if (out.change != null) {
            println(
                "%6.1fs  상태 %-5s → %-5s%s  (실제 %s, %.0fkm/h, %.0fm)".format(
                    sample.timestampMs / 1000.0,
                    out.change!!.from.color, out.change!!.to.color,
                    if (out.state.leftTurnArrow) " +좌회전" else "",
                    sample.groundTruth.color, sample.vehicle.speedKmh, sample.distanceM,
                ),
            )
        }
        for (a in out.alerts) {
            println("%6.1fs  🔔 [%s/%s] %s".format(a.timestampMs / 1000.0, a.level, a.tone, a.message))
        }
        if (sample.timestampMs % 2000 == 0L) {
            savePng(sample.frame, File(outDir, "${scenario.name}_%05d.png".format(sample.timestampMs)))
        }
    }
    println("  안정화된 상태 정확도: $correct / $known (UNKNOWN 제외)")
}

/** 무작위 장면(색·크기·위치·조명·노출)에 대한 단일 프레임 검출 정확도 */
private fun evaluateDetector(n: Int = 600) {
    val detector = TrafficLightDetector()
    val rnd = Random(42)
    val colors = listOf(SignalColor.RED, SignalColor.YELLOW, SignalColor.GREEN)
    val confusion = HashMap<Pair<SignalColor, SignalColor>, Int>()
    var arrowHit = 0; var arrowTotal = 0
    repeat(n) { i ->
        val truth = colors[rnd.nextInt(colors.size)]
        val arrow = truth == SignalColor.RED && rnd.nextFloat() < 0.4f
        val spec = SceneSpec(
            signal = SignalState(truth, arrow),
            lightCenterX = 200f + rnd.nextFloat() * 560f,
            lightCenterY = 30f + rnd.nextFloat() * 200f,
            lampRadius = 3f + rnd.nextFloat() * 14f,
            lighting = Lighting.entries[rnd.nextInt(3)],
            exposure = 0.85f + rnd.nextFloat() * 0.3f,
            noiseSigma = 4f + rnd.nextFloat() * 8f,
            seed = i,
        )
        val det = detector.detect(SyntheticSceneGenerator.render(spec))
        confusion.merge(truth to det.color, 1, Int::plus)
        if (arrow) { arrowTotal++; if (det.leftTurnArrow) arrowHit++ }
    }
    println("\n=== 단일 프레임 검출 평가 (무작위 합성 장면 $n 장)")
    val predicted = colors + SignalColor.UNKNOWN
    println("실제\\예측  " + predicted.joinToString("") { "%-9s".format(it) })
    for (t in colors) {
        println("%-10s".format(t) + predicted.joinToString("") { "%-9d".format(confusion[t to it] ?: 0) })
    }
    val acc = colors.sumOf { confusion[it to it] ?: 0 }.toFloat() / n
    val wrongColor = confusion.filter { (k, _) -> k.second != SignalColor.UNKNOWN && k.first != k.second }.values.sum()
    println("정확도 %.1f%%, 다른 색으로 오인 %d건, 좌회전 화살표 검출 %d/%d".format(acc * 100, wrongColor, arrowHit, arrowTotal))
}

private fun savePng(frame: Frame, file: File) {
    val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
    ImageIO.write(img, "png", file)
}
