package com.signalassist.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.signalassist.core.Gear
import com.signalassist.core.VehicleState
import kotlin.math.abs

/**
 * 표준 AAOS Car API(CarPropertyManager)로 차속·기어를 읽는다.
 * Pleos Connect 는 AAOS 기반이고 Android API 호환을 유지하므로, Pleos 차량/에뮬레이터에서도
 * 이 구현이 기본 동작한다. (AAOS 에뮬레이터: Extended controls > Car data 에서 속도/기어 변경 가능)
 */
class CarPropertyVehicleDataSource(private val context: Context) : VehicleDataSource {
    override val name = "Android Car API"

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    @Volatile private var speedKmh = 0f
    @Volatile private var gear = Gear.UNKNOWN

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speedKmh = abs(value.value as Float) * 3.6f
                VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR ->
                    gear = mapGear(value.value as Int)
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "property error id=$propertyId area=$areaId")
        }
    }

    override fun start() {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            if (ready) register(c) else propertyManager = null
        }
    }

    private fun register(c: Car) {
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        propertyManager = pm
        runCatching {
            pm.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { Log.w(TAG, "speed not available: ${it.message}") }
        val gearProp = if (pm.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) != null) {
            VehiclePropertyIds.GEAR_SELECTION
        } else {
            VehiclePropertyIds.CURRENT_GEAR
        }
        runCatching {
            pm.registerCallback(callback, gearProp, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "gear not available: ${it.message}") }
    }

    override fun current(nowMs: Long) = VehicleState(speedKmh = speedKmh, gear = gear)

    override fun stop() {
        propertyManager?.unregisterCallback(callback)
        propertyManager = null
        car?.disconnect()
        car = null
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
