package com.myen88.wearkey.domain

import com.myen88.wearkey.ble.Decision
import com.myen88.wearkey.ble.TeslaBleClient
import com.myen88.wearkey.ble.VehicleLink
import com.myen88.wearkey.data.WatchKeyStore
import com.myen88.wearkey.keystore.AndroidKeystoreKey
import com.myen88.wearkey.protocol.TeslaProtocol
import com.myen88.wearkey.protocol.crypto.ProtocolException
import com.myen88.wearkey.protocol.crypto.ResponseWindow
import com.myen88.wearkey.protocol.crypto.SessionOutOfSyncException
import com.myen88.wearkey.protocol.crypto.VehicleSession
import com.myen88.wearkey.protocol.message.FaultText
import com.myen88.wearkey.protocol.message.InfotainmentCommands
import com.myen88.wearkey.protocol.message.LockSnapshot
import com.myen88.wearkey.protocol.message.RoutableMessages
import com.myen88.wearkey.protocol.message.VcsecCommands
import com.myen88.wearkey.protocol.message.VcsecExpect
import com.myen88.wearkey.protocol.message.VcsecInterpret
import com.myen88.wearkey.protocol.message.VcsecRead
import com.myen88.wearkey.protocol.randomBytes
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class VehicleBusyException : ProtocolException("The car is busy. Try again.")

class VehicleCommander(
    private val ble: TeslaBleClient,
    private val key: AndroidKeystoreKey,
    private val store: WatchKeyStore,
) {
    suspend fun pair(
        vin: String,
        formFactor: Vcsec.KeyFormFactor,
        onPhase: (String) -> Unit,
    ): String {
        return ble.use(vin) { link ->
            onPhase("Asking the car to add this key…")
            val routing = randomBytes(TeslaProtocol.ADDRESS_LENGTH)
            val uuid = randomBytes(TeslaProtocol.UUID_LENGTH)
            val request = RoutableMessages.unsigned(
                domain = TeslaProtocol.VEHICLE_SECURITY,
                payload = VcsecCommands.addKey(key.publicUncompressed(), formFactor = formFactor),
                routingAddress = routing,
                uuid = uuid,
            )
            var detail = "The car enrolled this key."
            link.exchange(request, 45.seconds) { frame ->
                val message = RoutableMessages.parse(frame) ?: return@exchange Decision.CONTINUE
                if (!RoutableMessages.matches(message, routing, uuid)) return@exchange Decision.CONTINUE
                val body = unsignedBody(message)
                when (val read = VcsecInterpret.read(body, VcsecExpect.WHITELIST)) {
                    is VcsecRead.Waiting -> {
                        onPhase("Tap your key card on the center console, then confirm on the car screen.")
                        Decision.CONTINUE
                    }
                    is VcsecRead.Success -> {
                        detail = read.detail ?: detail
                        Decision.DONE
                    }
                    is VcsecRead.Failure -> throw ProtocolException(read.message)
                    VcsecRead.Ignore -> Decision.CONTINUE
                }
            }
            onPhase("Checking that the car accepted the key…")
            Live(link, vin).handshake(TeslaProtocol.VEHICLE_SECURITY.number)
            detail
        }
    }

    suspend fun refresh(vin: String): CarReading = retryBusy {
        ble.use(vin) { link -> Live(link, vin).reading(includeBattery = true) }
    }

    suspend fun lock(vin: String): LockSnapshot = retryBusy {
        ble.use(vin) { link ->
            val live = Live(link, vin)
            live.rke(Vcsec.RKEAction_E.RKE_ACTION_LOCK)
            live.status()
        }
    }

    suspend fun unlock(vin: String): LockSnapshot = retryBusy {
        ble.use(vin) { link ->
            val live = Live(link, vin)
            live.rke(Vcsec.RKEAction_E.RKE_ACTION_UNLOCK)
            live.status()
        }
    }

    suspend fun openTrunk(vin: String): LockSnapshot = retryBusy {
        ble.use(vin) { link ->
            val live = Live(link, vin)
            live.closure(VcsecCommands.openTrunk())
            live.status()
        }
    }

    suspend fun openFrunk(vin: String): LockSnapshot = retryBusy {
        ble.use(vin) { link ->
            val live = Live(link, vin)
            live.closure(VcsecCommands.openFrunk())
            live.status()
        }
    }

    suspend fun climate(vin: String, on: Boolean) {
        retryBusy {
            ble.use(vin) { link ->
                Live(link, vin).infotainment(InfotainmentCommands.climate(on), needsData = false)
            }
        }
    }

    private suspend fun <T> retryBusy(block: suspend () -> T): T {
        return try {
            block()
        } catch (_: VehicleBusyException) {
            delay(600)
            block()
        }
    }

    private inner class Live(
        private val link: VehicleLink,
        private val vin: String,
    ) {
        private val sessions = HashMap<Int, VehicleSession>()

        suspend fun reading(includeBattery: Boolean): CarReading {
            val lock = status()
            if (!includeBattery) return CarReading(lock, null, null)
            return try {
                CarReading(lock, readCharge(), null)
            } catch (error: ProtocolException) {
                if (error.message?.contains("did not respond") != true) {
                    return CarReading(lock, null, error.message)
                }
                try {
                    rke(Vcsec.RKEAction_E.RKE_ACTION_WAKE_VEHICLE)
                    delay(1_500)
                    CarReading(lock, readCharge(), null)
                } catch (again: ProtocolException) {
                    CarReading(
                        lock,
                        null,
                        again.message ?: "Battery and climate need the car awake. Lock and unlock still use the security controller.",
                    )
                }
            }
        }

        private suspend fun readCharge(): Int? =
            infotainment(InfotainmentCommands.chargeState(), needsData = true)

        suspend fun status(): LockSnapshot {
            unsignedStatus()?.let { return it }
            val body = authorized(vcsec, VcsecCommands.status(), 8.seconds) { payload ->
                when (val read = VcsecInterpret.read(payload, VcsecExpect.STATUS)) {
                    is VcsecRead.Success -> Decision.DONE
                    is VcsecRead.Failure -> throw ProtocolException(read.message)
                    is VcsecRead.Waiting -> Decision.CONTINUE
                    VcsecRead.Ignore -> Decision.CONTINUE
                }
            }
            return (VcsecInterpret.read(body, VcsecExpect.STATUS) as? VcsecRead.Success)?.lock
                ?: throw ProtocolException("The car did not report the lock state.")
        }

        suspend fun rke(action: Vcsec.RKEAction_E) {
            authorized(vcsec, VcsecCommands.rke(action), 8.seconds) { payload ->
                terminalRke(payload)
            }
        }

        suspend fun closure(payload: ByteArray) {
            authorized(vcsec, payload, 8.seconds) { body -> terminalRke(body) }
        }

        suspend fun infotainment(payload: ByteArray, needsData: Boolean): Int? {
            val body = authorized(info, payload, 12.seconds) { incoming ->
                terminalInfo(incoming, needsData)
            }
            if (body.isEmpty()) return null
            val response = InfotainmentCommands.parseResponse(body)
                ?: throw ProtocolException("The car sent a response the watch could not read.")
            InfotainmentCommands.requireOk(response)
            return InfotainmentCommands.batteryPercent(response)
        }

        suspend fun handshake(domain: Int): VehicleSession {
            val domainEnum = UniversalMessage.Domain.forNumber(domain)
                ?: throw ProtocolException("Unknown vehicle domain.")
            val routing = if (domain == vcsec) {
                randomBytes(TeslaProtocol.ADDRESS_LENGTH)
            } else {
                link.infotainmentRouting
            }
            val uuid = randomBytes(TeslaProtocol.UUID_LENGTH)
            val request = RoutableMessages.sessionInfoRequest(
                domain = domainEnum,
                clientPublicKey = key.publicUncompressed(),
                routingAddress = routing,
                uuid = uuid,
            )
            val frame = link.exchange(request, 12.seconds) { bytes ->
                val message = RoutableMessages.parse(bytes) ?: return@exchange Decision.CONTINUE
                if (!RoutableMessages.matches(message, routing, uuid)) return@exchange Decision.CONTINUE
                if (message.getPayloadCase() == UniversalMessage.RoutableMessage.PayloadCase.SESSION_INFO) {
                    Decision.DONE
                } else {
                    Decision.CONTINUE
                }
            }
            val message = RoutableMessages.parse(frame)
                ?: throw ProtocolException("The car's handshake could not be parsed.")
            FaultText.faultOf(message)?.let { throw ProtocolException(FaultText.message(it)) }
            val session = VehicleSession.open(
                response = message,
                vin = vin,
                domain = domain,
                requestUuid = uuid,
                clientPublicKey = key.publicUncompressed(),
                observedAtUnixSeconds = now(),
                ecdhX = key::ecdhSecret,
            )
            sessions[domain] = session
            store.save(session.snapshot())
            return session
        }

        private suspend fun unsignedStatus(): LockSnapshot? {
            val routing = randomBytes(TeslaProtocol.ADDRESS_LENGTH)
            val uuid = randomBytes(TeslaProtocol.UUID_LENGTH)
            val request = RoutableMessages.unsigned(
                TeslaProtocol.VEHICLE_SECURITY,
                VcsecCommands.status(),
                routing,
                uuid,
            )
            var lock: LockSnapshot? = null
            try {
                link.exchange(request, 5.seconds) { frame ->
                    val message = RoutableMessages.parse(frame) ?: return@exchange Decision.CONTINUE
                    if (!RoutableMessages.matches(message, routing, uuid)) return@exchange Decision.CONTINUE
                    if (FaultText.faultOf(message) != null) return@exchange Decision.DONE
                    if (message.hasSignatureData() && message.getSignatureData().hasAESGCMResponseData()) {
                        return@exchange Decision.DONE
                    }
                    val body = if (message.hasProtobufMessageAsBytes()) {
                        message.getProtobufMessageAsBytes().toByteArray()
                    } else {
                        ByteArray(0)
                    }
                    when (val read = VcsecInterpret.read(body, VcsecExpect.STATUS)) {
                        is VcsecRead.Success -> {
                            lock = read.lock
                            Decision.DONE
                        }
                        is VcsecRead.Failure -> throw ProtocolException(read.message)
                        is VcsecRead.Waiting -> Decision.CONTINUE
                        VcsecRead.Ignore -> Decision.CONTINUE
                    }
                }
            } catch (error: ProtocolException) {
                if (error.message?.contains("did not respond") == true) return null
                throw error
            }
            return lock
        }

        private suspend fun authorized(
            domain: Int,
            payload: ByteArray,
            timeout: Duration,
            interpret: (ByteArray) -> Decision,
        ): ByteArray {
            return try {
                send(domain, payload, timeout, interpret)
            } catch (error: SessionOutOfSyncException) {
                if (!error.resynced) {
                    sessions.remove(domain)
                    store.clear(domain)
                }
                send(domain, payload, timeout, interpret)
            }
        }

        private suspend fun send(
            domain: Int,
            payload: ByteArray,
            timeout: Duration,
            interpret: (ByteArray) -> Decision,
        ): ByteArray {
            val session = session(domain)
            val routing = if (domain == vcsec) {
                randomBytes(TeslaProtocol.ADDRESS_LENGTH)
            } else {
                link.infotainmentRouting
            }
            val command = session.encrypt(payload, now(), routing)
            store.save(session.snapshot())
            val window = ResponseWindow()
            var body = ByteArray(0)
            link.exchange(command.bytes, timeout) { frame ->
                val message = RoutableMessages.parse(frame) ?: return@exchange Decision.CONTINUE
                if (!RoutableMessages.matches(message, command.routingAddress, command.uuid)) {
                    return@exchange Decision.CONTINUE
                }
                val resynced = session.tryResync(message, command.uuid, now())
                if (resynced) store.save(session.snapshot())
                val fault = FaultText.faultOf(message)
                val opened = try {
                    session.openResponse(message, command.requestHash, window)
                } catch (error: ProtocolException) {
                    if (fault != null && FaultText.isSessionFault(fault)) {
                        throw SessionOutOfSyncException(FaultText.message(fault), resynced)
                    }
                    throw error
                }
                if (fault != null) {
                    when {
                        FaultText.isSessionFault(fault) ->
                            throw SessionOutOfSyncException(FaultText.message(fault), resynced)
                        FaultText.isBusy(fault) -> throw VehicleBusyException()
                        else -> throw ProtocolException(FaultText.message(fault))
                    }
                }
                body = opened
                interpret(opened)
            }
            return body
        }

        private suspend fun session(domain: Int): VehicleSession {
            sessions[domain]?.let { return it }
            val restored = store.load(domain)?.let { snapshot ->
                runCatching {
                    VehicleSession.restore(snapshot, vin, key.publicUncompressed(), key::ecdhSecret)
                }.getOrNull()
            }
            if (restored != null) {
                sessions[domain] = restored
                return restored
            }
            return handshake(domain)
        }

        private fun terminalRke(payload: ByteArray): Decision {
            return when (val read = VcsecInterpret.read(payload, VcsecExpect.RKE)) {
                is VcsecRead.Success -> Decision.DONE
                is VcsecRead.Waiting -> Decision.CONTINUE
                is VcsecRead.Failure -> throw ProtocolException(read.message)
                VcsecRead.Ignore -> Decision.CONTINUE
            }
        }

        private fun terminalInfo(payload: ByteArray, needsData: Boolean): Decision {
            if (payload.isEmpty()) return if (needsData) Decision.CONTINUE else Decision.DONE
            val response = InfotainmentCommands.parseResponse(payload) ?: return Decision.CONTINUE
            InfotainmentCommands.requireOk(response)
            if (needsData && !response.hasVehicleData()) return Decision.CONTINUE
            if (!needsData && !response.hasActionStatus() && !response.hasVehicleData()) {
                return Decision.CONTINUE
            }
            return Decision.DONE
        }
    }

    private fun unsignedBody(message: UniversalMessage.RoutableMessage): ByteArray {
        FaultText.faultOf(message)?.let { throw ProtocolException(FaultText.message(it)) }
        if (message.hasSignatureData() && message.getSignatureData().hasAESGCMResponseData()) {
            throw ProtocolException("The car encrypted a response before a session existed.")
        }
        return if (message.hasProtobufMessageAsBytes()) {
            message.getProtobufMessageAsBytes().toByteArray()
        } else {
            ByteArray(0)
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private companion object {
        val vcsec = TeslaProtocol.VEHICLE_SECURITY.number
        val info = TeslaProtocol.INFOTAINMENT.number
    }
}

data class CarReading(val lock: LockSnapshot, val batteryPercent: Int?, val note: String?)
