package com.signalassist.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.signalassist.core.Gear
import com.signalassist.core.VehicleState
import kotlin.math.abs

/**
 * AAOS 표준 Car API 연결 도우미. (Pleos Connect 는 AAOS 기반이며 Android API 호환을 유지)
 */
internal class CarConnection(private val context: Context, private val onReady: (Car) -> Unit) {
    private var car: Car? = null

    fun connect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready -> if (ready) onReady(c) }
        } else {
            car = runCatching { Car.createCar(context) }.getOrNull()?.also(onReady)
        }
    }

    fun disconnect() {
        car?.disconnect()
        car = null
    }
}

/**
 * 차속·기어. Pleos Vehicle State 에는 차속/기어 API 가 없어 표준 프로퍼티로 읽는다.
 * - 차속: PERF_VEHICLE_SPEED (CAR_SPEED 런타임 권한)
 * - 기어: GEAR_SELECTION (없으면 CURRENT_GEAR). 둘 다 못 읽으면 Gear.UNKNOWN -> 차속만으로 판단
 *
 * Pleos 에뮬레이터는 차속 시뮬레이션을 지원하지 않으므로 [speedOverrideKmh] 로 시연용 차속을 줄 수 있다.
 */
class CarPropertyVehicleDataSource(
    private val context: Context,
    private val speedOverrideKmh: () -> Float? = { null },
) : VehicleDataSource {
    override val name = "Car API (차속·기어)"

    private var propertyManager: CarPropertyManager? = null
    private val connection = CarConnection(context, ::register)

    @Volatile private var speedKmh: Float? = null
    @Volatile private var gear = Gear.UNKNOWN

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speedKmh = abs(value.value as Float) * 3.6f
                VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR -> gear = mapGear(value.value as Int)
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "property error id=$propertyId area=$areaId")
        }
    }

    override fun start() = connection.connect()

    private fun register(c: Car) {
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        propertyManager = pm
        runCatching {
            pm.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { Log.w(TAG, "speed not available: ${it.message}") }
        val gearProp = if (runCatching { pm.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) }.getOrNull() != null) {
            VehiclePropertyIds.GEAR_SELECTION
        } else {
            VehiclePropertyIds.CURRENT_GEAR
        }
        runCatching {
            pm.registerCallback(callback, gearProp, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "gear not available: ${it.message}") }
    }

    override fun current(nowMs: Long) =
        VehicleState(speedKmh = speedOverrideKmh() ?: speedKmh ?: 0f, gear = gear)

    /** 차속을 한 번이라도 실제로 받았는지 (에뮬레이터에서는 false) */
    val hasRealSpeed get() = speedKmh != null

    override fun stop() {
        runCatching { propertyManager?.unregisterCallback(callback) }
        propertyManager = null
        connection.disconnect()
    }

    private fun mapGear(g: Int): Gear = when (g) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // DRIVE, 1~9단, LOW 등
    }

    private companion object {
        const val TAG = "CarVehicleData"
    }
}
