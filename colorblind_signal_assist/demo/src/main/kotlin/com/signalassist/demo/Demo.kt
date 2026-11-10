package com.signalassist.demo

import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingAssistEngine
import com.signalassist.core.Frame
import com.signalassist.core.Lighting
import com.signalassist.core.SceneSpec
import com.signalassist.core.ScenarioPlayer
import com.signalassist.core.Scenario
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
    val profile = DriverProfile(scenario.targetNeeds)
    println("\n=== 시나리오: ${scenario.titleKo} — ${scenario.description}")
    println("    프로필: ${profile.needs.joinToString { it.labelKo }}")
    val player = ScenarioPlayer(scenario)
    val engine = DrivingAssistEngine(profile)
    var correct = 0
    var known = 0
    for (sample in player.samples()) {
        val out = engine.process(sample.toInput())
        val signal = out.signal
        val truth = sample.groundTruth
        if (signal != null && truth != null && signal.state.color != SignalColor.UNKNOWN) {
            known++
            if (signal.state.color == truth.color) correct++
        }
        signal?.change?.let { c ->
            println(
                "%6.1fs  신호 %-7s → %-7s%s  (실제 %s, %.0fkm/h, %.0fm)".format(
                    sample.timestampMs / 1000.0, c.from.color, c.to.color,
                    if (c.to.leftTurnArrow) " +좌회전" else "",
                    truth?.color ?: "-", sample.vehicle.speedKmh, sample.distanceM,
                ),
            )
        }
        for ((a, plan) in out.deliveries) {
            val channels = listOfNotNull(
                "소리".takeIf { plan.tone }, "음성".takeIf { plan.voice },
                (if (plan.dimmed) "화면깜빡임(야간 감광)" else "화면깜빡임").takeIf { plan.visualFlash },
                "큰글씨".takeIf { plan.largeText },
            ).joinToString("+").let { if (plan.silent) "화면 표시만(중재)" + (if (plan.largeText) "+큰글씨" else "") else it }
            println("%6.1fs  🔔 %s %s  [%s/%s → %s]".format(a.timestampMs / 1000.0, a.symbol, a.message, a.level, a.tone, channels))
        }
        if (sample.timestampMs % 2000 == 0L) {
            savePng(sample.frame, File(outDir, "${scenario.name}_%05d.png".format(sample.timestampMs)))
        }
    }
    if (known > 0) println("  안정화된 신호 상태 정확도: $correct / $known (UNKNOWN 제외)")
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
