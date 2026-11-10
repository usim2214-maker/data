package ai.parkingguide.core

/**
 * 차량 제원 (사이드미러 제외 전폭, 전장, 휠베이스). 값은 참고용 근사치.
 * Pleos Vehicle SDK의 CarInfo는 VIN만 주므로 VIN → 차종 조회 또는 사용자 설정으로 고른다.
 */
data class VehicleSpec(
    val name: String,
    val widthCm: Double,
    val lengthCm: Double,
    val wheelbaseCm: Double = 0.0,
    /** 핸들 각도 / 앞바퀴 각도. Pleos SteeringWheel API는 '앞바퀴 각도'를 주므로 핸들 각도로 바꿀 때 쓴다. */
    val steeringRatio: Double = 14.0,
    val maxWheelAngleDeg: Double = 35.0,
) {
    val wheelbase get() = if (wheelbaseCm > 0) wheelbaseCm else lengthCm * 0.59
    val rearOverhangCm get() = (lengthCm - wheelbase) * 0.53
    val maxSteerWheelDeg get() = maxWheelAngleDeg * steeringRatio

    /** Pleos SteeringWheel(앞바퀴 각도, 왼쪽 -) → 핸들 회전각(오른쪽 +). */
    fun steeringWheelFromFrontWheel(frontWheelDeg: Double) = frontWheelDeg * steeringRatio

    companion object {
        val PRESETS: Map<String, VehicleSpec> = listOf(
            VehicleSpec("casper", 159.5, 359.5, 240.0),
            VehicleSpec("ray", 159.5, 359.5, 252.0),
            VehicleSpec("avante", 182.5, 465.0, 272.0),
            VehicleSpec("kona", 182.5, 435.0, 266.0),
            VehicleSpec("sonata", 186.0, 491.0, 284.0),
            VehicleSpec("k5", 186.0, 490.5, 285.0),
            VehicleSpec("tucson", 186.5, 463.0, 275.5),
            VehicleSpec("sportage", 186.5, 466.0, 275.5),
            VehicleSpec("k8", 187.5, 501.5, 289.5),
            VehicleSpec("grandeur", 188.0, 503.5, 289.5),
            VehicleSpec("ioniq5", 189.0, 463.5, 300.0),
            VehicleSpec("ev6", 189.0, 468.0, 290.0),
            VehicleSpec("santafe", 190.0, 483.0, 281.5),
            VehicleSpec("sorento", 190.0, 481.5, 281.5),
            VehicleSpec("palisade", 197.5, 499.5, 290.0),
            VehicleSpec("carnival", 199.5, 515.5, 309.0),
            VehicleSpec("staria", 199.5, 525.5, 327.5),
        ).associateBy { it.name }

        val DEFAULT = PRESETS.getValue("sonata")
    }
}
