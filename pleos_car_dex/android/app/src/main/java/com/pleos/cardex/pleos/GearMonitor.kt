package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener

/**
 * Pleos Vehicle SDK 로 기어 상태를 받아 '정차(P단) 여부'를 알려준다. 권한: pleos.car.permission.CAR_DRIVE
 * (클래스/메서드는 Vehicle 2.3.2 aar 의 실제 시그니처 기준)
 * 기어 API 는 SDK 에서 deprecated(Google GEAR_SELECTION 권고) → DrivingRestriction 을 우선 쓰고, 이건 보조 수단.
 */
@Suppress("DEPRECATION")
class GearMonitor(context: Context) {
    private val vehicle = runCatching { Vehicle(context.applicationContext) }.getOrNull()
    private var listener: GearSelectionListener? = null

    /** onParked(true=P단, false=주행 기어, null=알 수 없음). 사용 불가 환경이면 false 반환 */
    fun start(onParked: (Boolean?) -> Unit): Boolean {
        val v = vehicle ?: return false
        return runCatching {
            v.initialize()
            val gear = v.getGear()
            gear.getCurrentGearSelectionState({ g -> onParked(g?.toParked()) }, { e -> Log.w(TAG, "gear get", e) })
            val l = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = onParked(gear?.toParked())
                override fun onFailed(e: Exception) { Log.w(TAG, "gear listener", e) }
            }
            gear.registerGearSelection(l)
            listener = l
            true
        }.getOrElse { Log.w(TAG, "Vehicle SDK unavailable", it); false }
    }

    fun release() {
        val v = vehicle ?: return
        runCatching { listener?.let { v.getGear().unregisterGearSelection(it) } }
        runCatching { v.release() }
    }

    private fun VehicleGear.toParked(): Boolean? = when (this) {
        VehicleGear.GEAR_PARK -> true
        VehicleGear.GEAR_UNKNOWN -> null
        else -> false
    }

    private companion object { const val TAG = "GearMonitor" }
}
