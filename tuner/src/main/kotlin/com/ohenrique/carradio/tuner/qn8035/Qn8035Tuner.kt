package com.ohenrique.carradio.tuner.qn8035

import com.ohenrique.carradio.tuner.FoundStation
import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.RdsInfo
import com.ohenrique.carradio.tuner.Region
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Sensitivity
import com.ohenrique.carradio.tuner.SignalInfo
import com.ohenrique.carradio.tuner.Tuner
import com.ohenrique.carradio.tuner.TunerActivity
import com.ohenrique.carradio.tuner.TunerState
import com.ohenrique.carradio.tuner.qn8035.Qn8035Registers as R
import com.ohenrique.carradio.tuner.rds.RdsDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Qn8035Config(
    /** Bus number of /dev/i2c-N, or null to probe every bus for a QN8035. */
    val i2cBusNumber: Int? = null,
    val i2cAddress: Int = R.DEFAULT_ADDRESS,
    /** Reference clock fed to the chip: a 32.768 kHz crystal, or a clock of 1 MHz or more. */
    val clockHz: Int = 32_768,
    /** True when the clock is a digital square wave instead of a crystal or sine wave. */
    val digitalClock: Boolean = false,
    /** Weakest signal a seek stops on, in dBµV, for LOC and DX. */
    val localThresholdDbuv: Int = 30,
    val dxThresholdDbuv: Int = 12,
)

/**
 * Drives a QN8035 directly over I2C, following the datasheet's tuning and CCA (hardware seek)
 * sequences. The chip's analog audio still has to be routed to the speakers by the head unit,
 * which on most units is the MCU's job when the stock radio app is opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qn8035Tuner(
    private val config: Qn8035Config,
    private val openBus: (Qn8035Config) -> I2cBus = { RootShellI2cBus.open(it.i2cBusNumber, it.i2cAddress) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : Tuner {

    override val name = "QN8035 (I2C)"

    private val _state = MutableStateFlow(TunerState())
    override val state: StateFlow<TunerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val busLock = Mutex()
    private val rds = RdsDecoder()
    private var bus: I2cBus? = null
    private var pollJob: Job? = null

    // Shadows of write-only registers.
    private var chStep = 0
    private var volCtl = 0
    private var lastRdsToggle = -1

    override suspend fun powerOn(region: Region, frequencyKhz: Int): Unit = io {
        if (_state.value.power == PowerState.ON) return@io
        _state.update { it.copy(power = PowerState.STARTING, region = region, error = null) }
        try {
            val bus = openBus(config).also { this.bus = it }
            val productId = bus.readRegister(R.CID2) ushr 2
            if (productId != R.PRODUCT_ID) {
                throw I2cException(
                    "O chip no endereço 0x%02x não respondeu como QN8035 (ID 0x%02x)".format(config.i2cAddress, productId),
                )
            }
            initialize(bus, region)
            tuneLocked(region.snap(frequencyKhz))
            _state.update { it.copy(power = PowerState.ON) }
        } catch (e: Exception) {
            closeBus()
            _state.update { it.copy(power = PowerState.ERROR, error = e.message ?: e.toString()) }
            return@io
        }
        startPolling()
    }

    override suspend fun powerOff() {
        pollJob?.cancelAndJoin()
        pollJob = null
        io {
            bus?.let { runCatching { it.writeRegister(R.SYSTEM1, R.STNBY) } }
            closeBus()
            _state.update { it.copy(power = PowerState.OFF, activity = TunerActivity.IDLE, rds = RdsInfo.EMPTY) }
        }
    }

    override suspend fun tune(frequencyKhz: Int): Unit = io {
        if (bus == null) return@io
        guard { tuneLocked(_state.value.region.snap(frequencyKhz)) }
    }

    override suspend fun seek(direction: SeekDirection, wrap: Boolean): FoundStation? = io {
        if (bus == null) return@io null
        val region = _state.value.region
        val origin = _state.value.frequencyKhz
        _state.update { it.copy(activity = TunerActivity.SEEKING) }
        var result: FoundStation? = null
        var finished = false
        try {
            guard {
                val step = region.stepKhz
                val (first, second) = when (direction) {
                    SeekDirection.UP -> (origin + step to region.maxKhz) to (region.minKhz to origin - step)
                    SeekDirection.DOWN -> (origin - step to region.minKhz) to (region.maxKhz to origin + step)
                }
                val foundKhz = ccaSearch(first.first, first.second, region)
                    ?: if (wrap) ccaSearch(second.first, second.second, region) else null
                tuneLocked(foundKhz ?: origin)
                if (foundKhz != null) result = FoundStation(foundKhz, readSignal().rssiDbuv)
                finished = true
            }
        } finally {
            if (!finished) withContext(NonCancellable) { runCatching { guard { tuneLocked(origin) } } }
            _state.update { it.copy(activity = TunerActivity.IDLE) }
        }
        result
    }

    override suspend fun setMuted(muted: Boolean): Unit = io {
        volCtl = if (muted) volCtl or R.MUTE_EN else volCtl and R.MUTE_EN.inv()
        bus?.let { guard { it.writeRegister(R.VOL_CTL, volCtl) } }
        _state.update { it.copy(muted = muted) }
    }

    override suspend fun setForceMono(forceMono: Boolean): Unit = io {
        _state.update { it.copy(forceMono = forceMono) }
        bus?.let { guard { it.writeRegister(R.SYSTEM1, receiveMode()) } }
    }

    override suspend fun setSensitivity(sensitivity: Sensitivity): Unit = io {
        _state.update { it.copy(sensitivity = sensitivity) }
    }

    override suspend fun setRdsEnabled(enabled: Boolean): Unit = io {
        _state.update { it.copy(rdsEnabled = enabled, rds = RdsInfo.EMPTY) }
        rds.reset()
        bus?.let { guard { it.writeRegister(R.SYSTEM1, receiveMode()) } }
    }

    private fun initialize(bus: I2cBus, region: Region) {
        bus.writeRegister(R.SYSTEM1, R.SWRST)
        bus.writeRegister(R.CCA, ccaRegister())
        val (xtalDiv, pllDlt) = R.clockDividers(config.clockHz)
        bus.writeRegister(R.XTAL_DIV0, xtalDiv and 0xFF)
        bus.writeRegister(R.XTAL_DIV1, ((pllDlt and 0x1F) shl 3) or ((xtalDiv ushr 8) and 0x7))
        bus.writeRegister(R.XTAL_DIV2, (pllDlt ushr 5) and 0xFF)
        volCtl = R.GAIN_ANA_MAX or (if (region.deemphasisMicros == 75) R.TC_75US else 0) or
            (if (_state.value.muted) R.MUTE_EN else 0)
        bus.writeRegister(R.VOL_CTL, volCtl)
        bus.writeRegister(R.INT_CTRL, R.RDS_ONLY)
        chStep = R.seekStepBits(region.stepKhz) shl 6
    }

    private fun tuneLocked(frequencyKhz: Int) {
        val bus = requireNotNull(bus)
        val channel = R.channelOf(frequencyKhz)
        chStep = (chStep and 0xFC) or ((channel ushr 8) and 0x3)
        bus.writeRegister(R.CH, channel and 0xFF)
        bus.writeRegister(R.CH_STEP, chStep)
        bus.writeRegister(R.SYSTEM1, receiveMode())
        rds.reset()
        lastRdsToggle = -1
        _state.update { it.copy(frequencyKhz = frequencyKhz, rds = RdsInfo.EMPTY) }
    }

    /**
     * Runs one hardware channel scan (CCA) from [startKhz] to [stopKhz]; the chip scans downwards
     * when start is above stop. Returns the first valid channel, or null.
     */
    private suspend fun ccaSearch(startKhz: Int, stopKhz: Int, region: Region): Int? {
        if (startKhz !in region || stopKhz !in region) return null
        val bus = requireNotNull(bus)
        val start = R.channelOf(startKhz)
        val stop = R.channelOf(stopKhz)
        chStep = (R.seekStepBits(region.stepKhz) shl 6) or
            (((stop ushr 8) and 0x3) shl 4) or
            (((start ushr 8) and 0x3) shl 2) or
            ((start ushr 8) and 0x3)
        bus.writeRegister(R.CH, start and 0xFF)
        bus.writeRegister(R.CH_START, start and 0xFF)
        bus.writeRegister(R.CH_STOP, stop and 0xFF)
        bus.writeRegister(R.CH_STEP, chStep)
        bus.writeRegister(R.CCA, ccaRegister())
        bus.writeRegister(R.SYSTEM1, receiveMode(seek = true))

        var status: Int
        var waitedMs = 0L
        var sawScan = false
        while (true) {
            delay(CCA_POLL_MS)
            waitedMs += CCA_POLL_MS
            status = bus.readRegister(R.STATUS1)
            val fsm = (status ushr R.FSM_SHIFT) and R.FSM_MASK
            if (fsm == R.FSM_RXCCA) sawScan = true
            if (fsm == R.FSM_RECEIVING && (sawScan || waitedMs >= CCA_START_GRACE_MS)) break
            if (waitedMs >= CCA_TIMEOUT_MS) throw I2cException("O QN8035 não terminou a busca a tempo")
        }
        if (status and R.RXCCA_FAIL != 0) return null

        val channel = bus.readRegister(R.CH) or ((bus.readRegister(R.CH_STEP) and 0x3) shl 8)
        val foundKhz = region.snap(R.frequencyOf(channel))
        val low = minOf(startKhz, stopKhz)
        val high = maxOf(startKhz, stopKhz)
        return foundKhz.takeIf { it in low..high }
    }

    private fun receiveMode(seek: Boolean = false): Int {
        val state = _state.value
        var value = R.RXREQ or (if (seek) R.CHSC else R.CCA_CH_DIS)
        if (state.rdsEnabled) value = value or R.RDSEN
        if (state.forceMono) value = value or R.FORCE_MO
        return value
    }

    private fun ccaRegister(): Int {
        val threshold = when (_state.value.sensitivity) {
            Sensitivity.LOCAL -> config.localThresholdDbuv
            Sensitivity.DX -> config.dxThresholdDbuv
        }
        return (if (config.digitalClock) R.XTAL_INJ else 0) or R.ccaThresholdBits(threshold)
    }

    private fun readSignal(): SignalInfo {
        val bus = requireNotNull(bus)
        val status = bus.readRegister(R.STATUS1)
        val signal = SignalInfo(
            rssiDbuv = (bus.readRegister(R.RSSISIG) - R.RSSI_OFFSET).coerceAtLeast(0),
            snrDb = bus.readRegister(R.SNR),
            stereo = status and R.ST_MO_RX == 0,
        )
        _state.update { it.copy(signal = signal) }
        return signal
    }

    private fun startPolling() {
        pollJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(POLL_MS)
                if (!busLock.tryLock()) continue
                try {
                    if (tick++ % SIGNAL_EVERY_TICKS == 0) readSignal()
                    if (_state.value.rdsEnabled) pollRds()
                } catch (e: Exception) {
                    _state.update { it.copy(power = PowerState.ERROR, error = e.message ?: e.toString()) }
                    break
                } finally {
                    busLock.unlock()
                }
            }
        }
    }

    private fun pollRds() {
        val bus = requireNotNull(bus)
        val status = bus.readRegister(R.STATUS2)
        val toggle = status and R.RDS_RXTXUPD
        if (toggle == lastRdsToggle) return
        val firstRead = lastRdsToggle == -1
        lastRdsToggle = toggle
        if (firstRead) return
        val bytes = bus.readRegisters(R.RDSD0, 8)
        // STATUS2[3:0] holds RDS0ERR..RDS3ERR, block A in bit 3.
        val blocks = Array(4) { block ->
            val failed = status and (1 shl (3 - block)) != 0
            if (failed) null else (bytes[block * 2] shl 8) or bytes[block * 2 + 1]
        }
        if (rds.onGroup(blocks[0], blocks[1], blocks[2], blocks[3])) {
            _state.update { it.copy(rds = rds.info) }
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        busLock.withLock { block() }
    } catch (e: I2cException) {
        _state.update { it.copy(error = e.message) }
        throw e
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatcher) { block() }

    private fun closeBus() {
        bus?.close()
        bus = null
    }

    private companion object {
        const val POLL_MS = 40L
        const val SIGNAL_EVERY_TICKS = 6
        const val CCA_POLL_MS = 20L
        const val CCA_START_GRACE_MS = 200L
        const val CCA_TIMEOUT_MS = 15_000L
    }
}
