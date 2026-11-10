package ai.parkingguide.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * 차량 신호 모음.
 * - 조향각: Pleos Vehicle SDK SteeringWheel.registerSteeringWheelAngle
 *   ※ 이 값은 '앞바퀴 조향각'(도, 왼쪽 -)이고 핸들 회전각이 아니다. VehicleSpec.steeringWheelFromFrontWheel로 바꿔 쓴다.
 * - 기어(R단): AAOS VehiclePropertyIds.GEAR_SELECTION
 *   (Pleos Gear API는 @hide·deprecated이며 "Google GEAR_SELECTION property를 쓰라"고 안내한다)
 * - 차속: AAOS VehiclePropertyIds.PERF_VEHICLE_SPEED (m/s). Pleos Odometer는 km 단위라 cm 추적엔 거칠다.
 */
class VehicleSignals(private val context: Context, private val onReverseChanged: (Boolean) -> Unit) {
    @Volatile var frontWheelDeg: Double? = null
        private set
    @Volatile var speedMps: Double = 0.0
        private set
    /** 기어 신호를 받은 적이 있는지. 없으면(에뮬레이터 등) 앱이 항상 후진 중으로 간주한다. */
    @Volatile var gearAvailable = false
        private set

    private val vehicle = Vehicle(context)
    private var car: Car? = null
    private var props: CarPropertyManager? = null

    private val steerListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            frontWheelDeg = angle?.toDouble()
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "steering angle unavailable", e)
        }
    }

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> {
                    gearAvailable = true
                    onReverseChanged((value.value as? Int) == VehicleGear.GEAR_REVERSE)
                }
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speedMps = abs((value.value as? Float ?: 0f).toDouble())
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "car property error $propertyId")
        }
    }

    fun start(canReadSpeed: Boolean) {
        vehicle.initialize()
        runCatching { vehicle.getSteeringWheel().registerSteeringWheelAngle(steerListener) }
            .onFailure { Log.w(TAG, "Pleos SteeringWheel not available", it) }
        runCatching {
            car = Car.createCar(context)
            props = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            props?.registerCallback(propCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (canReadSpeed) {
                props?.registerCallback(propCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            }
        }.onFailure { Log.w(TAG, "AAOS Car API not available", it) }
    }

    fun stop() {
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerListener) }
        runCatching { props?.unregisterCallback(propCallback) }
        runCatching { car?.disconnect() }
        vehicle.release()
    }

    private companion object { const val TAG = "VehicleSignals" }
}
