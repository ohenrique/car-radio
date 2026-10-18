package com.ohenrique.carradio

import android.content.Context
import com.ohenrique.carradio.data.TunerBackend
import com.ohenrique.carradio.nwd.NwdServiceClient
import com.ohenrique.carradio.tuner.fyt.Syu

/**
 * The head unit's own radio, for [TunerBackend.AUTO]: [TunerBackend.NWD] when the NWD radio service
 * is installed, else [TunerBackend.FYT] when com.syu.ms is, else null. NWD wins when a unit has
 * both: on an NWD unit, FYT's service gives no sound.
 */
@Suppress("DEPRECATION")
fun detectBackend(context: Context): TunerBackend? = when {
    NwdServiceClient.isInstalled(context) -> TunerBackend.NWD
    runCatching { context.packageManager.getPackageInfo(Syu.PACKAGE, 0) }.isSuccess -> TunerBackend.FYT
    else -> null
}
