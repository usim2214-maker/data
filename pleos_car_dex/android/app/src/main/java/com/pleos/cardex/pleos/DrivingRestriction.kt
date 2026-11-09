package com.pleos.cardex.pleos

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

/**
 * Android Automotive 표준 '주행 중 UX 제한' (CarUxRestrictionsManager).
 * 차량이 주행 중이라 화면 조작을 제한해야 하면 requiresDO=true 를 준다. 별도 권한 불필요.
 * Pleos 기어 API 가 deprecated 되며 Google 표준 사용을 권고하므로 1순위로 사용한다.
 */
class DrivingRestriction(context: Context) {
    private val appContext = context.applicationContext
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    /** 사용 불가(비 오토모티브 환경 등)면 false */
    fun start(onRestricted: (Boolean) -> Unit): Boolean = runCatching {
        car = Car.createCar(appContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            if (!ready) return@createCar
            runCatching {
                val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                manager = m
                m.registerListener { r -> onRestricted(r.isRequiresDistractionOptimization) }
                onRestricted(m.currentCarUxRestrictions.isRequiresDistractionOptimization)
            }.onFailure { Log.w(TAG, "UX restrictions", it) }
        }
        true
    }.getOrElse { Log.w(TAG, "Car API unavailable", it); false }

    fun release() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
    }

    private companion object { const val TAG = "DrivingRestriction" }
}
