package com.signalassist.app.vehicle

import android.car.Car
import android.car.VehicleAreaSeat
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.signalassist.core.CabinState
import com.signalassist.core.TurnSignal
import java.util.concurrent.ConcurrentHashMap

/**
 * 실내·차체 상태.
 *
 * ■ Pleos Vehicle State SDK (권장, 3rd-party 앱이 읽을 수 있는 경로)
 *   | CabinState 필드          | Pleos Vehicle State API          | 권한 그룹              |
 *   |--------------------------|----------------------------------|------------------------|
 *   | driverSeatbeltBuckled    | getCurrentSeatBeltBuckledState   | pleos...CAR_SEATS      |
 *   | seatbeltWarning          | hasSeatBeltNotBuckledWarning     | pleos...CAR_SAFETY     |
 *   | doorOpen                 | getCurrentDoorPosition           | pleos...CAR_DOORS      |
 *   | lowTirePressure          | getLowTirePressure               | pleos...CAR_SAFETY     |
 *   | turnSignal               | getCurrentTurnSignalState        | pleos...CAR_LIGHTS     |
 *   | headlightsOn             | getCurrentLowBeamLightState      | pleos...CAR_LIGHTS     |
 *   | rearSeatOccupied         | getCurrentSeatOccupancy          | pleos...CAR_SEATS      |
 *   (브레이크 페달은 getCurrentBrakePedalStatus / CAR_DRIVE)
 *   TODO(Pleos): SDK AAR 을 추가한 뒤 [readPleos] 에서 위 API 를 호출해 값을 채운다.
 *
 * ■ AAOS 표준 프로퍼티 (대체 경로)
 *   같은 정보를 표준 VehiclePropertyIds 로도 구독한다. 대부분 시스템 권한이 필요해 일반 앱에서는
 *   실패할 수 있으며, 실패한 항목은 null 로 남아 해당 알림 규칙만 꺼진다.
 *   Pleos 에뮬레이터 Virtual Console(adb forward tcp:8088 tcp:8088)에서 도어·조명·방향지시등·좌석을 바꿔 시험한다.
 */
class PleosCabinDataSource(private val context: Context) : CabinDataSource {
    override val name = "Vehicle State (실내 상태)"

    private var propertyManager: CarPropertyManager? = null
    private val connection = CarConnection(context, ::register)

    @Volatile private var driverBelt: Boolean? = null
    @Volatile private var lowTire: Boolean? = null
    @Volatile private var turnSignal: TurnSignal? = null
    @Volatile private var headlights: Boolean? = null
    @Volatile private var night: Boolean? = null
    private val doors = ConcurrentHashMap<Int, Boolean>()
    private val rearSeats = ConcurrentHashMap<Int, Boolean>()

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            val value = v.value
            when (v.propertyId) {
                VehiclePropertyIds.SEAT_BELT_BUCKLED ->
                    if (v.areaId and DRIVER_SEAT != 0) driverBelt = value as Boolean
                VehiclePropertyIds.DOOR_POS -> doors[v.areaId] = (value as Int) > 0
                VehiclePropertyIds.CRITICALLY_LOW_TIRE_PRESSURE, VehiclePropertyIds.TIRE_PRESSURE -> Unit
                VehiclePropertyIds.TURN_SIGNAL_STATE -> turnSignal = when (value as Int) {
                    1 -> TurnSignal.RIGHT
                    2 -> TurnSignal.LEFT
                    else -> TurnSignal.NONE
                }
                VehiclePropertyIds.HAZARD_LIGHTS_STATE -> if ((value as Int) == 1) turnSignal = TurnSignal.HAZARD
                VehiclePropertyIds.HEADLIGHTS_STATE -> headlights = (value as Int) == 1
                VehiclePropertyIds.NIGHT_MODE -> night = value as Boolean
                VehiclePropertyIds.SEAT_OCCUPANCY ->
                    if (v.areaId and REAR_SEATS != 0) rearSeats[v.areaId] = (value as Int) == 1
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    override fun start() = connection.connect()

    private fun register(c: Car) {
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        propertyManager = pm
        val props = listOf(
            VehiclePropertyIds.SEAT_BELT_BUCKLED,
            VehiclePropertyIds.DOOR_POS,
            VehiclePropertyIds.TURN_SIGNAL_STATE,
            VehiclePropertyIds.HAZARD_LIGHTS_STATE,
            VehiclePropertyIds.HEADLIGHTS_STATE,
            VehiclePropertyIds.NIGHT_MODE,
            VehiclePropertyIds.SEAT_OCCUPANCY,
        )
        for (p in props) {
            runCatching { pm.registerCallback(callback, p, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                .onFailure { Log.i(TAG, "${VehiclePropertyIds.toString(p)} unavailable: ${it.message}") }
        }
    }

    override fun current(nowMs: Long): CabinState {
        val fromCarApi = CabinState(
            driverSeatbeltBuckled = driverBelt,
            doorOpen = if (doors.isEmpty()) null else doors.values.any { it },
            lowTirePressure = lowTire,
            turnSignal = turnSignal,
            headlightsOn = headlights,
            isDark = night,
            rearSeatOccupied = if (rearSeats.isEmpty()) null else rearSeats.values.any { it },
        )
        return readPleos(fromCarApi)
    }

    /** Pleos Vehicle State 값으로 덮어쓰기. SDK 연결 전에는 입력을 그대로 돌려준다. */
    private fun readPleos(base: CabinState): CabinState {
        // TODO(Pleos): 예) base.copy(seatbeltWarning = vehicleState.hasSeatBeltNotBuckledWarning(), ...)
        return base
    }

    override fun stop() {
        runCatching { propertyManager?.unregisterCallback(callback) }
        propertyManager = null
        connection.disconnect()
    }

    private companion object {
        const val TAG = "PleosCabin"
        const val DRIVER_SEAT = VehicleAreaSeat.SEAT_ROW_1_LEFT // 국내 좌핸들 기준
        const val REAR_SEATS = VehicleAreaSeat.SEAT_ROW_2_LEFT or VehicleAreaSeat.SEAT_ROW_2_CENTER or
            VehicleAreaSeat.SEAT_ROW_2_RIGHT or VehicleAreaSeat.SEAT_ROW_3_LEFT or
            VehicleAreaSeat.SEAT_ROW_3_CENTER or VehicleAreaSeat.SEAT_ROW_3_RIGHT
    }
}
