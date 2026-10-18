package com.ohenrique.carradio.tuner.nwd

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Radio on NWD head units (the K4811 of the Adak Link8wf, the K2501 and others), driven through
 * the vendor's `com.nwd.radio.service` the way the stock radio and the open NWD launchers do. No
 * root needed.
 *
 * The tuner chip sits behind the MCU, and the MCU also decides which source reaches the speakers:
 * the radio tunes silently until `com.nwd.kernel` makes it the source. So besides tuning, this
 * claims the source and checks in `mcu_current_source` that the switch took. The service's notices
 * do not reach every client, so everything they report is also polled.
 *
 * NWD builds differ in details the open clients disagree on: the frequency unit, how a source
 * claim is phrased, which call stops on the next station and which way its boolean goes. Those are
 * worked out at run time, and each decision goes to [trace] so a user can show what the unit did.
 *
 * @param dispatcher runs the service's getters, the Settings reads and the poll loop.
 * @param commandDispatcher runs the commands, so a command the vendor is slow to answer never
 *   holds up polling.
 * @param now a monotonic clock in milliseconds.
 * @param takeSpeakers false for automatic reconnects: power on then claims nothing and leaves
 *   another source playing.
 * @param inheritedUnitMute the head unit is muted by this app, through the tuner this one replaces
 *   after a lost connection ([holdsUnitMute]): this one lifts it as that one would have.
 * @param trace one line per decision, in Portuguese, for the diagnostics screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NwdTuner(
    private val connect: suspend () -> NwdService,
    private val timing: Timing = Timing(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val commandDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val takeSpeakers: Boolean = true,
    inheritedUnitMute: Boolean = false,
    private val trace: (String) -> Unit = {},
) : Tuner {

    data class Timing(
        val connectMs: Long = 8_000,
        /** How long a source claim gets to show in `mcu_current_source`, and the least time between two. */
        val claimMs: Long = 3_000,
        val claimPollMs: Long = 200,
        /** How long a tune waits for the service to report it is ready after a claim. */
        val readyMs: Long = 2_000,
        /** How long a tune waits to be confirmed before it counts as ignored. */
        val confirmMs: Long = 1_600,
        val tunePollMs: Long = 100,
        /** After a tune, frequency notices that contradict it are this old at most. */
        val staleMs: Long = 1_500,
        /** Some services drop band changes that come faster than this. */
        val bandGapMs: Long = 550,
        val bandConfirmMs: Long = 2_000,
        val maxBandChanges: Int = 6,
        val seekStartMs: Long = 1_500,
        val seekMaxMs: Long = 30_000,
        val seekPollMs: Long = 150,
        val settleMs: Long = 1_200,
        /** How long to wait for the last frequency notice after the service says a seek ended. */
        val seekTailMs: Long = 300,
        val pollMs: Long = 1_000,
    )

    override val name = "NWD (com.nwd.radio.service)"

    override val managesAudioSource = true

    override val reportsSignalStrength = false

    override val usesHeadUnitBand = true

    /**
     * A mute this app sent to the whole head unit is still in force, as far as this tuner knows. It
     * outlives a lost connection, for the tuner that replaces this one ([inheritedUnitMute]).
     */
    val holdsUnitMute: Boolean get() = mutedByApp

    private val _state = MutableStateFlow(TunerState())
    override val state: StateFlow<TunerState> = _state.asStateFlow()

    /** Held by tunes, seeks and restores; the poll loop skips a pass while it is taken. */
    private val operation = Mutex()

    // Cleared from a Binder thread when the service dies.
    @Volatile private var service: NwdService? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var sender: LatestOnlySender<TuneRequest>? = null
    @Volatile private var restoreJob: Job? = null

    @Volatile private var radioType = UNKNOWN_TYPE
    @Volatile private var plan = NwdBandPlan.fallback(Region.BRAZIL)
    @Volatile private var planSource = PlanSource.DEFAULT
    /** Lives as long as this instance, which the app replaces on every reconnect. */
    private val seekStrategy = NwdSeekStrategy()

    // What the service reported. Written from Binder and main threads too.
    /** The latest frequency notice from any channel, before the stale filter. */
    private val seen = MutableStateFlow<NwdFrequency?>(null)
    /** The latest notice the stale filter let through. */
    @Volatile private var reading: NwdFrequency? = null
    /** The FM bank (FM1 to FM3) the service was last on; every tune passes it back. */
    @Volatile private var fmBand = Nwd.Band.FM1
    /** The FM station the service was last on while the unit was awake. */
    @Volatile private var station: Int? = null
    @Volatile private var stationBeforeSleep: Int? = null
    /**
     * The station to put back after the unit woke up on another source, once the radio is the
     * source again. Until then the dial keeps showing it, so the vendor's station is never saved.
     */
    @Volatile private var wakeRestore: Int? = null
    /** Bumped on every station change, so a radio text read across one can be dropped. */
    @Volatile private var stationEpoch = 0
    @Volatile private var radioState: Int? = null
    @Volatile private var scanState = 0
    @Volatile private var busySince: Long? = null
    /** Set when a wait for [Nwd.State.NORMAL] timed out, so tunes stop waiting for it. */
    @Volatile private var readyNeverSeen = false

    // The MCU, from Settings.System and the vendor's broadcasts.
    private val source = MutableStateFlow<Int?>(null)
    @Volatile private var unitMuted: Boolean? = null
    /** A mute sent by this app is in force. It silences the whole head unit, every source included. */
    @Volatile private var mutedByApp = false
    /** Taken over by the first power on. */
    @Volatile private var inheritedMute = inheritedUnitMute
    @Volatile private var mcuState: Int? = null
    @Volatile private var lastClaimAt: Long? = null
    /**
     * Both ways of claiming went out and `mcu_current_source` never showed the radio. The radio may
     * play anyway, with a setting that does not follow, so tunes and seeks stop waiting for it.
     */
    @Volatile private var claimUnverified = false
    @Volatile private var lastBandChangeAt: Long? = null

    // The stale window of the last tune: notices of anything else are older than it.
    @Volatile private var pendingRaw: Int? = null
    @Volatile private var pendingSince = 0L
    @Volatile private var pendingMatched = false

    // The seek in progress.
    @Volatile private var seekFrom: Int? = null
    @Volatile private var firstMove: Int? = null
    @Volatile private var busySeen = false
    /** A tune handed over before the seek that had not landed when it started (see onFrequency). */
    @Volatile private var strayRaw: Int? = null

    // What the app asked for. Settings can arrive before the service is connected, so they are
    // kept here and sent once it is, and only when the service reports something different.
    @Volatile private var wantedSensitivity: Sensitivity? = null
    @Volatile private var wantedMono = false
    /** Stereo is left to the service until the user asks for mono once. */
    @Volatile private var monoTouched = false

    @Volatile private var lastFailure: String? = null

    override suspend fun powerOn(region: Region, frequencyKhz: Int) {
        if (_state.value.power == PowerState.ON) return
        _state.update {
            it.copy(
                power = PowerState.STARTING,
                region = region,
                frequencyKhz = region.snap(frequencyKhz),
                error = null,
                connectionLost = false,
            )
        }
        // A mute this app set stays this app's to lift, across a lost connection (see onSource).
        val heldMute = mutedByApp || inheritedMute
        inheritedMute = false
        resetSession(region)
        mutedByApp = heldMute
        try {
            val service = withTimeout(timing.connectMs) { connect() }
            val scope = CoroutineScope(
                SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log("Erro: ${e.message ?: e}") },
            )
            this.scope = scope
            this.service = service
            sender = LatestOnlySender(scope, commandDispatcher, ::send)
            service.listener = ::onEvent
            service.onDisconnected = { fail(CONNECTION_LOST, connectionLost = true) }

            // First, as the stock clients do. Polling makes up for a refusal.
            optional { command { it.registerCallback() } }
            radioType = optional { query { it.getRadioType() } } ?: UNKNOWN_TYPE
            log("Tipo do rádio: ${typeName(radioType)}")
            readBandPlan()
            // The one getter that must work: without it nothing else would either.
            readFrequency()
            optional { readState() }
            optional { readNearOn() }
            optional { readPilot() }
            readSettings()
            // Undone meanwhile, by hand or by another source taking over (see onSource).
            if (unitMuted == false) mutedByApp = false

            // An automatic reconnect leaves another source, and the unit's mute, as they are.
            val radioWanted = takeSpeakers || source.value == Nwd.Source.RADIO
            if (takeSpeakers && !inCall()) {
                claim()
                if (unitMuted != false) unmuteUnit()
            }
            if (radioWanted) ensureFm()
            optional { syncSensitivity() }
            optional { syncStereo() }
            _state.update { if (it.power == PowerState.STARTING) it.copy(power = PowerState.ON) else it }
            if (_state.value.power != PowerState.ON) return
            startPolling(scope)
            // The service forgets the station when the unit powers off, so it is always put back.
            if (radioWanted) restoreJob = scope.launch { restore(frequencyKhz) }
        } catch (e: TimeoutCancellationException) {
            failOnce(NO_ANSWER)
        } catch (e: CancellationException) {
            failOnce("Cancelado")
            throw e
        } catch (e: Detached) {
            // Powered off or failed while starting; that state stands.
        } catch (e: Exception) {
            failOnce(e.message ?: e.toString())
        }
    }

    override suspend fun powerOff() {
        val service = service
        this.service = null
        scope?.cancel()
        scope = null
        sender = null
        restoreJob = null
        if (service != null) {
            withContext(commandDispatcher + NonCancellable) {
                // The radio stays the MCU's source, as on FYT; but the unit is not left muted.
                if (mutedByApp) runCatching { service.setUnitMuted(false) }
                runCatching { service.unregisterCallback() }
                service.listener = null
                service.onDisconnected = null
                runCatching { service.close() }
            }
        }
        mutedByApp = false
        pendingRaw = null
        seekFrom = null
        _state.update { it.copy(power = PowerState.OFF, activity = TunerActivity.IDLE, rds = RdsInfo.EMPTY) }
    }

    override suspend fun tune(frequencyKhz: Int) {
        if (service == null) return
        cancelRestore()
        wakeRestore = null
        attached {
            operation.withLock { tuneLocked(_state.value.region.snap(frequencyKhz)) }
        }
    }

    override suspend fun seek(direction: SeekDirection, wrap: Boolean): FoundStation? {
        if (service == null) return null
        cancelRestore()
        wakeRestore = null
        return attached {
            operation.withLock { seekLocked(direction) }
        }
    }

    override suspend fun setMuted(muted: Boolean) {
        if (service == null) return
        attached {
            readSettings()
            if (muted) {
                // The mute silences the whole head unit, so it is only sent while the radio is what
                // plays, and taken back as soon as another source gets the speakers (see onSource).
                if (radioIsSource() && !inCall()) {
                    command { it.setUnitMuted(true) }
                    unitMuted = true
                    mutedByApp = true
                    log("Mudo da central ligado pelo app")
                }
            } else if (!inCall()) {
                if (source.value.let { it != null && it != Nwd.Source.RADIO }) claim()
                if (unitMuted != false || mutedByApp) unmuteUnit()
            }
            refreshMuted()
        }
    }

    override suspend fun reclaimSource() {
        if (service == null) return
        attached {
            readSettings()
            if (inCall() || radioIsSource()) return@attached
            log("Retomando a fonte da central (estava em ${source.value})")
            claim()
            // The lift of the app's mute, when the other source took over, was missed.
            if (mutedByApp) unmuteUnit()
            readSettings()
        }
    }

    override suspend fun setForceMono(forceMono: Boolean) {
        wantedMono = forceMono
        _state.update { it.copy(forceMono = forceMono) }
        quietly { syncStereo() }
    }

    override suspend fun setSensitivity(sensitivity: Sensitivity) {
        wantedSensitivity = sensitivity
        _state.update { it.copy(sensitivity = sensitivity) }
        quietly { syncSensitivity() }
    }

    override suspend fun setRdsEnabled(enabled: Boolean) {
        // Only the app's display: the service's RDS, AF and TA settings are never touched.
        _state.update { it.copy(rdsEnabled = enabled, rds = if (enabled) it.rds else RdsInfo.EMPTY) }
    }

    private suspend fun syncSensitivity() {
        val local = wantedSensitivity?.let { it == Sensitivity.LOCAL } ?: return
        if (service == null) return
        if (optional { readNearOn() } == local) return
        command { it.setNearOn(local) }
        log(if (local) "LOC ligado" else "LOC desligado (DX)")
        optional { readNearOn() }
    }

    /** The service's stereo setting is left alone until the user asks for mono once. */
    private suspend fun syncStereo() {
        if (service == null || (!wantedMono && !monoTouched)) return
        monoTouched = true
        val stereo = !wantedMono
        if (optional { query { it.isStereoOn() } } == stereo) return
        command { it.setStereoOn(stereo) }
        log(if (stereo) "Estéreo ligado" else "Estéreo desligado (mono)")
    }

    private suspend fun restore(frequencyKhz: Int) = attached {
        operation.withLock {
            val khz = _state.value.region.snap(frequencyKhz)
            if (!seen.value.isAt(plan.toRaw(khz))) log("Restaurando a estação salva: ${plan.toRaw(khz)}")
            tuneLocked(khz)
        }
    }

    private suspend fun cancelRestore() {
        restoreJob?.cancelAndJoin()
    }

    /**
     * Tunes to [frequencyKhz] and waits for the service to confirm it. A tune the service ignored
     * (the dial did not move and nothing else was going on) is sent once more; anything that moved
     * the dial elsewhere wins instead, since somebody else is turning it.
     */
    private suspend fun tuneLocked(frequencyKhz: Int, claim: Boolean = true) {
        awaitBusyEnd()
        if (claim) ensureSource()
        ensureFm()
        awaitReady()
        val raw = plan.toRaw(frequencyKhz)
        val before = seen.value
        if (before.isAt(raw) && sender?.idle == true) {
            pendingRaw = null
            accept(before!!)
            return
        }
        openWindow(raw)
        stationEpoch++
        // Shown straight away; a tune that does not land puts the real station back.
        _state.update { it.copy(frequencyKhz = plan.toKhz(raw), rds = RdsInfo.EMPTY) }
        try {
            val band = fmBand
            var confirmed = sendAndConfirm(raw, band)
            if (!confirmed && wasIgnored(before)) {
                log("O serviço ignorou a sintonia em $raw; reenviando uma vez")
                openWindow(raw)
                confirmed = sendAndConfirm(raw, band)
            }
            if (confirmed) {
                log("Sintonia confirmada: $raw")
                return
            }
            pendingRaw = null
            val actual = optional { readFrequency() }
            log("Sintonia em $raw não confirmada; o serviço está em ${describe(actual)}")
            if (actual == null || !Nwd.Band.isFm(actual.band) || actual.frequency <= 0) showStation()
        } catch (e: CancellationException) {
            // A newer tune is on its way (step taps cancel the one before): the window stays open
            // for it, and so does the dial.
            throw e
        } catch (e: Exception) {
            pendingRaw = null
            showStation()
            throw e
        }
    }

    private fun openWindow(raw: Int) {
        pendingRaw = raw
        pendingSince = now()
        pendingMatched = false
    }

    /** Hands the tune to the sender, then waits for a notice or a readback of [raw]. */
    private suspend fun sendAndConfirm(raw: Int, band: Int): Boolean {
        val sender = sender ?: throw Detached()
        val sent = sender.offer(TuneRequest(raw, band))
        // A slow vendor may still be busy with the previous tune; the readback tells either way.
        if (withTimeoutOrNull(timing.confirmMs) { sent.await() } == false) return false
        return withTimeoutOrNull(timing.confirmMs) {
            while (!pendingMatched && !readFrequency().isAt(raw)) delay(timing.tunePollMs)
            true
        } ?: false
    }

    /** Runs on [commandDispatcher], one tune at a time. */
    private fun send(request: TuneRequest) {
        val service = attachedService()
        // The window starts when the service gets the tune, not when it was queued.
        if (pendingRaw == request.raw) pendingSince = now()
        log("Sintonizando ${request.raw} (banda ${request.band})")
        service.setCurrentFrequency(request.raw, request.band, Nwd.NO_PRESET)
    }

    private suspend fun wasIgnored(before: NwdFrequency?): Boolean {
        val after = optional { readFrequency() }
        optional { readState() }
        if (busyNow()) return false
        return after?.frequency == before?.frequency && after?.band == before?.band
    }

    /** Shows the station the service last reported, after a tune that did not land there. */
    private fun showStation() {
        val raw = reading?.takeIf { Nwd.Band.isFm(it.band) && it.frequency > 0 }?.frequency ?: station ?: return
        _state.update { it.copy(frequencyKhz = plan.toKhz(raw)) }
    }

    private suspend fun seekLocked(direction: SeekDirection): FoundStation? {
        awaitBusyEnd()
        ensureSource()
        ensureFm()
        awaitReady()
        // A tune handed over just before (a cancelled tap, or the restore) may still be landing, and
        // its landing would read as this seek's first move: the way the seek went would be learned
        // wrong for the whole session.
        val handed = pendingRaw
        if (handed != null) {
            withTimeoutOrNull(timing.confirmMs) {
                while (!(sender ?: throw Detached()).idle || (!pendingMatched && !readFrequency().isAt(handed))) {
                    delay(timing.tunePollMs)
                }
            }
        }
        pendingRaw = null
        val origin = readFrequency()?.takeIf { Nwd.Band.isFm(it.band) && it.frequency > 0 }?.frequency
            ?: plan.toRaw(_state.value.frequencyKhz)
        strayRaw = handed?.takeIf { it != origin }
        _state.update { it.copy(activity = TunerActivity.SEEKING) }
        val end = try {
            val first = seekOnce(direction, origin)
            if (first.advice == NwdSeekStrategy.Advice.RETRY_OTHER) seekOnce(direction, first.end).end else first.end
        } finally {
            seekFrom = null
            strayRaw = null
            _state.update { it.copy(activity = TunerActivity.IDLE) }
        }
        // The service wraps around the band by itself, so wrap cannot be honoured here.
        return if (end != origin) FoundStation(plan.toKhz(end), null) else null
    }

    /** One call of the service's seek or search, watched until the dial stops. */
    private suspend fun seekOnce(direction: SeekDirection, from: Int): SeekRun {
        val call = seekStrategy.call
        val up = seekStrategy.argument(call, direction)
        seekFrom = from
        firstMove = null
        busySeen = false
        command { if (call == SeekCall.SEARCH) it.search(up) else it.seek(up) }
        if (awaitSeekStart(from)) awaitSeekDone()
        // Where a late tune landed, when it did before the seek moved (see onFrequency).
        val start = seekFrom ?: from
        val end = optional { readFrequency() }?.takeIf { Nwd.Band.isFm(it.band) && it.frequency > 0 }?.frequency
            ?: reading?.takeIf { Nwd.Band.isFm(it.band) && it.frequency > 0 }?.frequency
            ?: start
        val channels = channelsMoved(start, end, direction)
        val wentUp = firstMove?.let { shortestMove(start, it) > 0 }
        val advice = seekStrategy.record(call, direction, wentUp, channels, busySeen)
        log(
            "Busca ${call.label}($up) para ${if (direction == SeekDirection.UP) "cima" else "baixo"}: " +
                "$start → $end, ${signed(channels)} canais, ocupado: ${if (busySeen) "sim" else "não"}" +
                if (advice == NwdSeekStrategy.Advice.RETRY_OTHER) "; tentando ${seekStrategy.call.label}" else "",
        )
        return SeekRun(end, advice)
    }

    /** Waits for a seek to show: the service busy, or the dial moving. False when neither happened. */
    private suspend fun awaitSeekStart(from: Int): Boolean = withTimeoutOrNull(timing.seekStartMs) {
        while (true) {
            optional { readState() }
            if (busySeen) break
            optional { readFrequency() }
            if (firstMove != null) break
            delay(timing.seekPollMs)
        }
        true
    } ?: false

    /**
     * Waits for a seek to end: the service no longer busy and, unless it said so through its scan
     * state too, the dial still for [Timing.settleMs].
     */
    private suspend fun awaitSeekDone() {
        withTimeoutOrNull(timing.seekMaxMs) {
            var last = seen.value?.frequency
            var stillSince = now()
            while (true) {
                val state = optional { readState() }
                val frequency = optional { readFrequency() }?.frequency
                if (frequency != last) {
                    last = frequency
                    stillSince = now()
                }
                val busy = state != null && Nwd.State.isBusy(state)
                if (!busy && ((busySeen && scanState == 0) || now() - stillSince >= timing.settleMs)) break
                delay(timing.seekPollMs)
            }
        }
        if (busySeen) {
            delay(timing.seekTailMs)
            optional { readFrequency() }
        }
    }

    /**
     * Channels from [from] to [to], signed. A move of more than half the band against [direction]
     * went past the band edge in [direction], since the service wraps around it.
     */
    private fun channelsMoved(from: Int, to: Int, direction: SeekDirection): Int {
        val plan = plan
        var channels = (to - from) / plan.stepRaw
        if (direction == SeekDirection.UP && channels < -plan.channels / 2) channels += plan.channels
        if (direction == SeekDirection.DOWN && channels > plan.channels / 2) channels -= plan.channels
        return channels
    }

    /** Channels from [from] to [to] the short way round, signed. */
    private fun shortestMove(from: Int, to: Int): Int {
        val plan = plan
        val channels = (to - from) / plan.stepRaw
        return when {
            channels > plan.channels / 2 -> channels - plan.channels
            channels < -plan.channels / 2 -> channels + plan.channels
            else -> channels
        }
    }

    /**
     * Lets a seek the service is still running (one whose request was cancelled here) finish,
     * since no command stops it safely and the dial would keep moving under a new tune.
     */
    private suspend fun awaitBusyEnd() {
        optional { readState() }
        if (!busyNow()) return
        // A state stuck on busy is waited for once, not on every tune.
        val budget = timing.seekMaxMs - (now() - (busySince ?: now()))
        if (budget <= 0) return
        log("Esperando o serviço terminar uma busca")
        withTimeoutOrNull(budget) {
            while (busyNow()) {
                delay(timing.seekPollMs)
                optional { readState() }
            }
        }
        delay(timing.seekTailMs)
    }

    /**
     * Waits for the service to report it is ready, as it may not be right after a claim. A service
     * that never says so is not waited for again.
     */
    private suspend fun awaitReady() {
        if (readyNeverSeen) return
        val ready = withTimeoutOrNull(timing.readyMs) {
            while (optional { readState() } != Nwd.State.NORMAL) delay(timing.tunePollMs)
            true
        }
        if (ready == null) {
            readyNeverSeen = true
            log("O serviço não ficou pronto (estado $radioState) em ${timing.readyMs} ms; enviando mesmo assim")
        }
    }

    /**
     * Makes the radio the MCU's source: one way of asking, a wait for `mcu_current_source` to show
     * it, and the other way if it did not. Which way goes first depends on the radio type (on MCU
     * units the kernel's source request, elsewhere the radio app's announcement). Returns whether
     * the radio is the source as far as can be told.
     */
    private suspend fun claim(): Boolean {
        if (inCall()) {
            log("Em ligação: a fonte fica com o telefone")
            return false
        }
        val before = readSource()
        if (before == Nwd.Source.RADIO) return true
        val last = lastClaimAt
        if (last != null && now() - last < timing.claimMs) {
            // Asking again this soon could start the radio twice; the earlier request may still land.
            log("Pedido de fonte recente; só esperando por ele")
            return before == null || awaitRadioSource(timing.claimMs - (now() - last))
        }
        for (step in claimSteps()) {
            val started = now()
            sendClaim(step)
            if (before == null) {
                log("Fonte ilegível: ${step.label} enviado sem confirmação")
                return true
            }
            if (awaitRadioSource(timing.claimMs)) {
                log("${step.label}: fonte $before → ${Nwd.Source.RADIO} em ${now() - started} ms")
                return true
            }
            log("${step.label}: a fonte continua ${source.value} depois de ${timing.claimMs} ms")
        }
        if (!claimUnverified) log("A fonte não mostrou o rádio depois dos dois pedidos; sintonias e buscas não esperam mais por ela")
        claimUnverified = true
        return false
    }

    private fun claimSteps() = if (radioType <= Nwd.RadioType.MCU) {
        listOf(ClaimStep.REQUEST, ClaimStep.APP_IN)
    } else {
        listOf(ClaimStep.APP_IN, ClaimStep.REQUEST)
    }

    private suspend fun sendClaim(step: ClaimStep) {
        lastClaimAt = now()
        command {
            when (step) {
                ClaimStep.REQUEST -> it.requestSource(Nwd.Source.RADIO)
                ClaimStep.APP_IN -> {
                    it.sendAppInOut(Nwd.App.RADIO, Nwd.App.OPERATION_IN, Nwd.App.EVENT_NONE)
                    // Radios that run on the SoC rather than the MCU also want to hear it plays.
                    if (radioType > Nwd.RadioType.MCU) it.sendMediaPlay(Nwd.App.RADIO)
                }
            }
        }
    }

    private suspend fun awaitRadioSource(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (readSource() != Nwd.Source.RADIO) {
            withTimeoutOrNull(timing.claimPollMs) { source.first { it == Nwd.Source.RADIO } }
        }
        true
    } ?: false

    /**
     * Claims the source for a tune or a seek when it is known to be another one. After a claim the
     * source never confirmed, only the first way of asking goes out again, no more often than
     * [Timing.claimMs], and nothing is waited for, so the dial does not freeze on every tap.
     */
    private suspend fun ensureSource() {
        val current = readSource()
        if (current == null || current == Nwd.Source.RADIO) return
        if (!claimUnverified) {
            claim()
            return
        }
        if (inCall()) return
        val last = lastClaimAt
        if (last != null && now() - last < timing.claimMs) return
        val step = claimSteps().first()
        sendClaim(step)
        log("${step.label} reenviado sem esperar: a fonte é $current")
    }

    /**
     * Brings the service back to FM when something (the stock radio, a key) left it on AM. Returns
     * whether it is on FM; when not, the tune that follows asks for FM1 itself.
     */
    private suspend fun ensureFm(): Boolean {
        val current = readFrequency() ?: return true
        if (Nwd.Band.isFm(current.band)) return true
        log("O serviço está em AM (banda ${current.band})")
        // On Allwinner services the band command means something else, so cycling goes first.
        val fm = if (radioType == Nwd.RadioType.ALLWINNER) {
            cycleToFm() || selectFm()
        } else {
            selectFm() || cycleToFm()
        }
        if (!fm) log("Não voltou para FM; a sintonia pede a banda FM1")
        return fm
    }

    private suspend fun selectFm(): Boolean {
        command { it.sendRadioCommand(Nwd.Command.SELECT_BAND + Nwd.Band.FM1, Nwd.Command.ON) }
        val fm = awaitBand(Nwd.Band::isFm)
        log("sendRadioCommand(${Nwd.Command.SELECT_BAND}, ${Nwd.Command.ON}) ${if (fm) "voltou para FM" else "não mudou a banda"}")
        return fm
    }

    private suspend fun cycleToFm(): Boolean {
        repeat(timing.maxBandChanges) {
            val before = seen.value?.band ?: return false
            lastBandChangeAt?.let { delay((it + timing.bandGapMs - now()).coerceAtLeast(0)) }
            lastBandChangeAt = now()
            command { it.changeBand() }
            if (!awaitBand { it != before }) {
                log("changeBand não mudou a banda ($before)")
                return false
            }
            val band = seen.value?.band ?: return false
            log("changeBand: banda $before → $band")
            if (Nwd.Band.isFm(band)) return true
        }
        log("Desisti de voltar para FM depois de ${timing.maxBandChanges} trocas de banda")
        return false
    }

    private suspend fun awaitBand(accept: (Int) -> Boolean): Boolean = withTimeoutOrNull(timing.bandConfirmMs) {
        while (readFrequency()?.band?.let(accept) != true) delay(timing.tunePollMs)
        true
    } ?: false

    private suspend fun unmuteUnit() {
        command { it.setUnitMuted(false) }
        unitMuted = false
        mutedByApp = false
    }

    /** True also when the source cannot be read, since then there is nothing to check. */
    private fun radioIsSource(): Boolean = source.value.let { it == null || it == Nwd.Source.RADIO }

    private suspend fun inCall(): Boolean = optional { query { it.isPhoneCallActive() } } ?: false

    private fun busyNow(): Boolean = radioState?.let(Nwd.State::isBusy) == true || scanState != 0

    private fun awake(): Boolean = mcuState.let { it == null || it == Nwd.McuState.AWAKE }

    private fun startPolling(scope: CoroutineScope) {
        scope.launch {
            var pass = 0
            while (isActive) {
                delay(timing.pollMs)
                try {
                    optional { query { it.mcuState() } }?.let(::onMcuState)
                    // The vendor tears FM down while the unit goes to sleep: leave it alone then.
                    if (!awake()) continue
                    if (!operation.tryLock()) continue
                    try {
                        poll(pass++)
                    } finally {
                        operation.unlock()
                    }
                } catch (e: Detached) {
                    break
                }
            }
        }
    }

    private suspend fun poll(pass: Int) {
        optional { readFrequency() }
        optional { readState() }
        optional { readPilot() }
        if (pass % NEAR_ON_EVERY_PASSES == 0) optional { readNearOn() }
        if (_state.value.rdsEnabled) optional { pollRadioText() }
        readSettings()
    }

    private suspend fun pollRadioText() {
        if (!reading.let { it != null && Nwd.Band.isFm(it.band) } || windowOpen() || !showsReading()) return
        val epoch = stationEpoch
        val text = query { it.getRtMessage() }
        // The text of a station the dial left while it was being read would land on the new one.
        if (epoch == stationEpoch && _state.value.rdsEnabled) showRadioText(text)
    }

    private suspend fun readBandPlan() {
        val points = optional { query { it.getRadioPoint() } }
        val fromPoints = NwdBandPlan.fromRadioPoints(points)
        if (fromPoints != null) {
            usePlan(fromPoints, PlanSource.RADIO_POINT)
        } else {
            log("O serviço não informou a faixa FM (${points?.joinToString() ?: "nada"}); a unidade sai da frequência")
        }
    }

    private suspend fun readFrequency(): NwdFrequency? = query { it.getCurrentFrequency() }?.also(::onFrequency)

    private suspend fun readState(): Int = query { it.getRadioState() }.also(::onRadioState)

    private suspend fun readPilot(): Boolean = query { it.hasStereoPilot() }.also(::onPilot)

    private suspend fun readNearOn(): Boolean = query { it.isNearOn() }.also(::onNearOn)

    /** The source, from the setting when it can be read, else the last one a broadcast reported. */
    private suspend fun readSource(): Int? {
        optional { query { it.currentSource() } }?.let(::onSource)
        return source.value
    }

    private suspend fun readSettings() {
        readSource()
        optional { query { it.isUnitMuted() } }?.let {
            // Corrects a mute sent here that the unit did not apply, or one undone by hand.
            unitMuted = it
            refreshMuted()
        }
    }

    private fun onEvent(event: NwdEvent) {
        if (service == null) return
        when (event) {
            is NwdEvent.FrequencyChanged -> onFrequency(event.frequency)
            is NwdEvent.StateChanged -> onRadioState(event.state)
            is NwdEvent.ScanStateChanged -> {
                scanState = event.state
                if (event.state != 0 && seekFrom != null) busySeen = true
                updateBusy()
            }
            is NwdEvent.NearOnChanged -> onNearOn(event.on)
            is NwdEvent.StereoPilotChanged -> onPilot(event.stereo)
            is NwdEvent.RadioTextChanged ->
                if (_state.value.rdsEnabled && !windowOpen() && showsReading() && reading.let { it != null && Nwd.Band.isFm(it.band) }) {
                    showRadioText(event.text)
                }
            is NwdEvent.RadioPointChanged -> NwdBandPlan.fromRadioPoints(event.points)?.let {
                if (it != plan) usePlan(it, PlanSource.RADIO_POINT)
            }
            is NwdEvent.SourceChanged -> onSource(event.source)
            is NwdEvent.UnitMuteChanged -> {
                unitMuted = event.muted
                if (!event.muted) mutedByApp = false
                refreshMuted()
            }
            is NwdEvent.McuStateChanged -> onMcuState(event.state)
        }
    }

    /** Every frequency notice comes through here: callbacks, the broadcast and readbacks. */
    private fun onFrequency(frequency: NwdFrequency) {
        seen.value = frequency
        val from = seekFrom
        if (from != null && firstMove == null && Nwd.Band.isFm(frequency.band) &&
            frequency.frequency > 0 && frequency.frequency != from
        ) {
            if (frequency.frequency == strayRaw) {
                // The tune handed over before the seek landed only now: the seek moves from there.
                strayRaw = null
                seekFrom = frequency.frequency
            } else {
                firstMove = frequency.frequency
            }
        }
        val pending = pendingRaw
        if (pending != null) {
            when {
                Nwd.Band.isFm(frequency.band) && frequency.frequency == pending -> {
                    pendingRaw = null
                    pendingMatched = true
                }
                // Older than the tune that was just sent, and so is the station name with it.
                now() - pendingSince < timing.staleMs -> return
                else -> pendingRaw = null
            }
        }
        accept(frequency)
    }

    private fun accept(frequency: NwdFrequency) {
        val previous = reading
        reading = frequency
        // On AM the dial keeps showing the last FM station; an FM tune brings FM back.
        if (!Nwd.Band.isFm(frequency.band)) return
        fmBand = frequency.band
        // A cold front end reports 0.
        if (frequency.frequency <= 0) return
        if (planSource == PlanSource.DEFAULT) {
            NwdBandPlan.fromFrequency(frequency, _state.value.region)?.let { usePlan(it, PlanSource.FREQUENCY) }
        }
        val newStation = previous == null || !Nwd.Band.isFm(previous.band) || previous.frequency != frequency.frequency
        if (newStation) stationEpoch++
        if (awake()) station = frequency.frequency
        if (!showsReading()) return
        val khz = plan.toKhz(frequency.frequency)
        val name = cleanText(frequency.programService)
        _state.update {
            var rds = if (newStation) RdsInfo.EMPTY else it.rds
            // A name only ever goes with the station it came with, and a blank never clears one.
            if (name != null && it.rdsEnabled) rds = rds.copy(programService = name)
            it.copy(frequencyKhz = khz, rds = rds)
        }
    }

    private fun usePlan(newPlan: NwdBandPlan, from: PlanSource) {
        plan = newPlan
        planSource = from
        val unit = when (newPlan.scaleKhz) {
            1 -> "kHz"
            else -> "${newPlan.scaleKhz} kHz"
        }
        if (from == PlanSource.RADIO_POINT) {
            _state.update { it.copy(region = newPlan.region) }
            log(
                "Faixa FM do serviço: ${newPlan.minRaw} a ${newPlan.maxRaw}, passo ${newPlan.stepRaw}, " +
                    "em unidades de $unit (região ${newPlan.region})",
            )
        } else {
            log("Unidade de FM tirada da frequência atual: $unit")
        }
        reading?.let { current ->
            if (Nwd.Band.isFm(current.band) && current.frequency > 0) {
                _state.update { it.copy(frequencyKhz = newPlan.toKhz(current.frequency)) }
            }
        }
    }

    private fun onRadioState(state: Int) {
        radioState = state
        if (Nwd.State.isBusy(state) && seekFrom != null) busySeen = true
        if (state == Nwd.State.NORMAL) readyNeverSeen = false
        // A scan notice whose end never arrived: the service says it is idle, and no seek of ours runs.
        if (state == Nwd.State.NORMAL && seekFrom == null) scanState = 0
        updateBusy()
    }

    private fun updateBusy() {
        if (busyNow()) {
            if (busySince == null) busySince = now()
        } else {
            busySince = null
        }
    }

    private fun onPilot(stereo: Boolean) {
        _state.update { if (it.signal.stereo == stereo) it else it.copy(signal = SignalInfo(stereo = stereo)) }
    }

    private fun onNearOn(on: Boolean) {
        _state.update { it.copy(sensitivity = if (on) Sensitivity.LOCAL else Sensitivity.DX) }
    }

    private fun showRadioText(text: String?) {
        val clean = cleanText(text) ?: return
        _state.update { it.copy(rds = it.rds.copy(radioText = clean)) }
    }

    private fun onSource(id: Int) {
        val before = source.value
        source.value = id
        if (before != id) log("Fonte da central: $id")
        if (id == Nwd.Source.RADIO) {
            if (claimUnverified) log("A fonte mostrou o rádio; sintonias e buscas voltam a esperar pelo pedido de fonte")
            claimUnverified = false
            // Back on the radio after waking up elsewhere: its station goes back now.
            if (wakeRestore != null && awake() && restoreJob?.isActive != true) {
                restoreJob = scope?.launch { attached { restoreAfterWake() } }
            }
        }
        if (id != Nwd.Source.RADIO && mutedByApp) {
            // Another source took the speakers: it must not play into the radio's mute.
            mutedByApp = false
            unitMuted = false
            runCatching { service?.setUnitMuted(false) }
            log("Outra fonte ($id) assumiu: mudo do app desfeito")
        }
        refreshMuted()
    }

    private fun refreshMuted() {
        _state.update { it.copy(muted = unitMuted == true || !radioIsSource()) }
    }

    private fun onMcuState(state: Int) {
        val before = mcuState
        if (before == state) return
        mcuState = state
        log("Estado da MCU: $state")
        val wasAwake = before == null || before == Nwd.McuState.AWAKE
        if (wasAwake && state != Nwd.McuState.AWAKE) stationBeforeSleep = wakeRestore ?: station
        if (!wasAwake && state == Nwd.McuState.AWAKE) onWake()
    }

    /**
     * The vendor tears FM down when the unit sleeps and may wake up elsewhere. The station is put
     * back, without claiming: right away when the radio is the source, or when the source setting
     * is known not to follow it ([claimUnverified]), else once it is again (see onSource), unless
     * the user tunes or seeks first.
     */
    private fun onWake() {
        val scope = scope ?: return
        if (_state.value.power != PowerState.ON) return
        val target = stationBeforeSleep ?: station ?: return
        restoreJob?.cancel()
        wakeRestore = target
        restoreJob = scope.launch {
            attached {
                readSource()
                // A source setting that never showed the radio says nothing about what plays.
                if (!radioIsSource() && !claimUnverified) {
                    log("A central acordou com outra fonte (${source.value}); $target volta quando o rádio for a fonte")
                    return@attached
                }
                restoreAfterWake()
            }
        }
    }

    private suspend fun restoreAfterWake() = operation.withLock {
        val target = wakeRestore ?: return@withLock
        try {
            if (readFrequency().isAt(target)) return@withLock
            log("A central acordou fora da estação; voltando para $target")
            tuneLocked(plan.toKhz(target), claim = false)
        } finally {
            // From now on the dial shows what the service reports again (see accept). A restore
            // cancelled by a newer wake leaves the station to that wake's restore.
            if (wakeRestore == target && currentCoroutineContext().isActive) wakeRestore = null
        }
    }

    /** False while the unit sleeps or a wake restore is pending: the dial then holds its station. */
    private fun showsReading(): Boolean = awake() && wakeRestore == null

    private fun windowOpen(): Boolean = pendingRaw != null && now() - pendingSince < timing.staleMs

    private fun resetSession(region: Region) {
        plan = NwdBandPlan.fallback(region)
        planSource = PlanSource.DEFAULT
        radioType = UNKNOWN_TYPE
        seen.value = null
        reading = null
        fmBand = Nwd.Band.FM1
        station = null
        stationBeforeSleep = null
        wakeRestore = null
        radioState = null
        scanState = 0
        busySince = null
        readyNeverSeen = false
        source.value = null
        unitMuted = null
        mutedByApp = false
        mcuState = null
        lastClaimAt = null
        claimUnverified = false
        lastBandChangeAt = null
        pendingRaw = null
        seekFrom = null
        strayRaw = null
    }

    private fun failOnce(message: String) {
        if (_state.value.power != PowerState.ERROR) fail(message)
    }

    private fun fail(message: String, connectionLost: Boolean = false) {
        val service = service
        this.service = null
        scope?.cancel()
        scope = null
        sender = null
        restoreJob = null
        service?.listener = null
        service?.onDisconnected = null
        runCatching { service?.close() }
        pendingRaw = null
        seekFrom = null
        log("Erro: $message")
        _state.update {
            it.copy(power = PowerState.ERROR, activity = TunerActivity.IDLE, error = message, connectionLost = connectionLost)
        }
    }

    private fun attachedService(): NwdService = service ?: throw Detached()

    /** Runs a getter on [dispatcher], for the service this tuner is still attached to. */
    private suspend fun <T> query(call: (NwdService) -> T): T = withContext(dispatcher) { call(attachedService()) }

    /** Runs a command on [commandDispatcher], for the service this tuner is still attached to. */
    private suspend fun <T> command(call: (NwdService) -> T): T = withContext(commandDispatcher) { call(attachedService()) }

    /** [block]'s result, or null when the service refused it. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Detached) {
        throw e
    } catch (e: Exception) {
        val message = e.message ?: e.toString()
        // A refusal repeated by every poll goes to the trace once.
        if (message != lastFailure) log("Falhou: $message")
        lastFailure = message
        null
    }

    /** Ends quietly when the tuner is powered off, or loses the service, while [block] runs. */
    private suspend fun <T> attached(block: suspend () -> T): T? = try {
        block()
    } catch (e: Detached) {
        null
    }

    /** For the setters, which never throw. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Falhou: ${e.message ?: e}")
        }
    }

    private fun log(message: String) {
        runCatching { trace(message) }
    }

    private fun describe(frequency: NwdFrequency?) =
        frequency?.let { "${it.frequency} (banda ${it.band})" } ?: "lugar nenhum"

    private fun NwdFrequency?.isAt(raw: Int) = this != null && Nwd.Band.isFm(band) && frequency == raw

    /** The tuner was powered off, or lost the service, under a running operation. */
    private class Detached : IllegalStateException("O rádio foi desligado")

    private data class TuneRequest(val raw: Int, val band: Int)

    private class SeekRun(val end: Int, val advice: NwdSeekStrategy.Advice)

    private enum class ClaimStep(val label: String) {
        REQUEST("REQUEST_CHANGE_SOURCE(${Nwd.Source.RADIO})"),
        APP_IN("APP_IN_OUT(${Nwd.App.RADIO}, ${Nwd.App.OPERATION_IN}, ${Nwd.App.EVENT_NONE})"),
    }

    private enum class PlanSource { DEFAULT, FREQUENCY, RADIO_POINT }

    companion object {
        /** The error shown when the service went away while the radio was on; the app reconnects on it. */
        const val CONNECTION_LOST = "A conexão com o serviço de rádio da central (com.nwd.radio.service) caiu"

        private const val NO_ANSWER = "O serviço de rádio com.nwd.radio.service não respondeu. Essa central é NWD?"

        private const val UNKNOWN_TYPE = -1
        private const val NEAR_ON_EVERY_PASSES = 5

        private fun typeName(type: Int) = when (type) {
            Nwd.RadioType.MCU -> "0 (MCU)"
            Nwd.RadioType.ARM -> "1 (ARM)"
            Nwd.RadioType.ALLWINNER -> "2 (Allwinner)"
            Nwd.RadioType.SOC -> "3 (FM do SoC)"
            UNKNOWN_TYPE -> "desconhecido"
            else -> type.toString()
        }

        private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

        /**
         * A station name or radio text as the service has it, cleaned: control characters dropped,
         * trimmed. Null when nothing printable is left, or for the text "null" some services send.
         */
        internal fun cleanText(text: String?): String? {
            val clean = text?.filterNot { it.isISOControl() }?.trim() ?: return null
            if (clean.equals("null", ignoreCase = true)) return null
            return clean.takeIf { s -> s.any { !it.isWhitespace() && it != '�' } }
        }
    }
}
