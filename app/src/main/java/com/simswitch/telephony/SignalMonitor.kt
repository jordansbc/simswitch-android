package com.simswitch.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * Watches the radio on *every* active subscription at once and publishes a live snapshot per SIM.
 *
 * Phase 1 only observes — nothing here switches anything. The point is to find out, with real data
 * from the owner's phone, whether the standby SIM reports usable metrics. That answer decides how much
 * weight the scoring engine can put on live radio versus learned history.
 */
class SignalMonitor(private val context: Context) {

    private val _signals = MutableStateFlow<Map<Int, SubSignal>>(emptyMap())
    val signals: StateFlow<Map<Int, SubSignal>> = _signals

    private val executor = Executors.newSingleThreadExecutor()
    private val registrations = mutableMapOf<Int, Pair<TelephonyManager, TelephonyCallback>>()

    /** Last DDS we published, so [refreshDataSub] can report a change rather than just applying it. */
    private var lastKnownDataSub = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        val subs = context.getSystemService(SubscriptionManager::class.java) ?: return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        lastKnownDataSub = dataSub

        val active = runCatching { subs.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
        for (info in active) {
            val subId = info.subscriptionId
            _signals.update {
                it + (subId to SubSignal(
                    subId = subId,
                    slot = info.simSlotIndex,
                    carrier = info.carrierName?.toString().orEmpty().ifBlank { "sub $subId" },
                    isDataSub = subId == dataSub,
                ))
            }

            val subTm = tm.createForSubscriptionId(subId)
            val callback = SubCallback(subId)
            runCatching { subTm.registerTelephonyCallback(executor, callback) }
                .onSuccess { registrations[subId] = subTm to callback }
        }
    }

    fun stop() {
        registrations.forEach { (_, pair) ->
            runCatching { pair.first.unregisterTelephonyCallback(pair.second) }
        }
        registrations.clear()
    }

    /**
     * Re-reads which sub currently carries data, without tearing down the radio listeners.
     *
     * The default data subscription can move without this app touching it — the owner flipping it in
     * Settings, a carrier-driven change, or anything else. Reading it once at [start] left every
     * sample attributing throughput to whichever line happened to be the DDS at boot, which is how
     * a whole night of T-Mobile traffic ended up credited to SIM A. So this is called every
     * window, not only after a switch we performed ourselves.
     *
     * @return true if the data subscription changed since the last check.
     */
    fun refreshDataSub(): Boolean {
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        if (dataSub == lastKnownDataSub) return false
        lastKnownDataSub = dataSub
        _signals.update { map -> map.mapValues { (id, s) -> s.copy(isDataSub = id == dataSub) } }
        return true
    }

    private inner class SubCallback(private val subId: Int) :
        TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener {

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            // Prefer NR when the phone reports both — on a 5G SA/NSA connection the NR figures are
            // the ones describing the link actually carrying traffic.
            val nr = signalStrength.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
            val lte = signalStrength.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()

            update(subId) { current ->
                when {
                    nr != null -> current.copy(
                        rsrp = nr.ssRsrp.sane(),
                        rsrq = nr.ssRsrq.sane(),
                        sinr = nr.ssSinr.sane(),
                        level = nr.level,
                        updatedAt = System.currentTimeMillis(),
                    )

                    lte != null -> current.copy(
                        rsrp = lte.rsrp.sane(),
                        rsrq = lte.rsrq.sane(),
                        sinr = lte.rssnr.sane(),
                        level = lte.level,
                        updatedAt = System.currentTimeMillis(),
                    )

                    else -> current.copy(level = signalStrength.level, updatedAt = System.currentTimeMillis())
                }
            }
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            val ps = serviceState.networkRegistrationInfoList.firstOrNull {
                it.domain == NetworkRegistrationInfo.DOMAIN_PS &&
                    it.transportType == android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN
            }

            // Band numbers live on the cell identity, not on SignalStrength.
            val bands: List<Int> = when (val id = ps?.cellIdentity) {
                is CellIdentityNr -> id.bands.toList()
                is CellIdentityLte -> id.bands.toList()
                else -> emptyList()
            }

            val rat = ps?.accessNetworkTechnology?.let(::ratName) ?: "?"

            update(subId) {
                it.copy(
                    inService = serviceState.state == ServiceState.STATE_IN_SERVICE,
                    rat = rat,
                    bands = bands,
                    bandTier = Bands.tierOf(rat, bands),
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

        override fun onDisplayInfoChanged(info: android.telephony.TelephonyDisplayInfo) {
            // Distinguishes real 5G from an LTE anchor showing a 5G icon.
            val label = when (info.overrideNetworkType) {
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR-ADV"
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR-NSA"
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE-CA"
                android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE+"
                else -> ratName(info.networkType)
            }
            update(subId) { it.copy(rat = label, updatedAt = System.currentTimeMillis()) }
        }
    }

    private fun update(subId: Int, transform: (SubSignal) -> SubSignal) {
        _signals.update { map ->
            val current = map[subId] ?: return@update map
            map + (subId to transform(current))
        }
    }

    private companion object {
        /** The telephony stack uses Integer.MAX_VALUE / 0x7FFFFFFF to mean "no measurement". */
        fun Int.sane(): Int? = if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this

        fun ratName(type: Int): String = when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "?"
            else -> "type$type"
        }
    }
}
