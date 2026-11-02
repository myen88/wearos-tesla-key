package com.myen88.wearkey.protocol.crypto

import com.google.protobuf.InvalidProtocolBufferException
import com.myen88.wearkey.protocol.TeslaProtocol
import com.myen88.wearkey.protocol.message.RoutableMessages
import com.myen88.wearkey.protocol.randomBytes
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage

/**
 * One authenticated session with a single vehicle domain.
 * VCSEC and infotainment keep separate epochs, clocks, and counters.
 * The shared key is derived on demand and is not written to disk by this type.
 */
class VehicleSession(
    val domain: Int,
    val vin: String,
    val clientPublicKey: ByteArray,
    var sharedKey: ByteArray,
    var vehiclePublicKey: ByteArray,
    var epoch: ByteArray,
    var counter: Int,
    var clockOffsetSeconds: Long,
    var lastVehicleClock: Long,
) {
    fun expiresAt(unixSeconds: Long): Long =
        unixSeconds + clockOffsetSeconds + TeslaProtocol.COMMAND_LIFETIME_SECONDS

    /**
     * Builds the next encrypted command. The counter is incremented first,
     * matching vehicle-command's Signer.Encrypt, and must be persisted even
     * if the write later fails so the value is never reused.
     */
    fun encrypt(
        plaintext: ByteArray,
        unixSeconds: Long,
        routingAddress: ByteArray,
        uuid: ByteArray = randomBytes(TeslaProtocol.UUID_LENGTH),
        nonce: ByteArray = randomBytes(12),
    ): AuthorizedCommand {
        if (counter.toUInt() == UInt.MAX_VALUE) {
            throw ProtocolException("Session counter rolled over. Reconnect to the car.")
        }
        counter = (counter.toUInt() + 1u).toInt()
        val expires = expiresAt(unixSeconds)
        val metadata = SessionCrypto.commandMetadata(
            domain = domain,
            vin = vin,
            epoch = epoch,
            counter = counter,
            expiresAt = expires,
            flags = TeslaProtocol.FLAG_ENCRYPT_RESPONSE,
        )
        val sealed = SessionCrypto.seal(sharedKey, metadata, nonce, plaintext)
        val bytes = RoutableMessages.authorized(
            domain = domain,
            routingAddress = routingAddress,
            uuid = uuid,
            clientPublicKey = clientPublicKey,
            epoch = epoch,
            nonce = sealed.nonce,
            counter = counter,
            expiresAt = expires,
            tag = sealed.tag,
            ciphertext = sealed.ciphertext,
            flags = TeslaProtocol.FLAG_ENCRYPT_RESPONSE,
        )
        return AuthorizedCommand(
            bytes = bytes,
            requestHash = sealed.requestHash,
            uuid = uuid,
            routingAddress = routingAddress,
            counter = counter,
        )
    }

    /**
     * Returns the application payload. Encrypted responses are opened with
     * the request hash of the command that provoked them. Plaintext responses
     * (firmware that ignores FLAG_ENCRYPT_RESPONSE) are returned as-is.
     */
    fun openResponse(
        message: UniversalMessage.RoutableMessage,
        requestHash: ByteArray,
        window: ResponseWindow,
    ): ByteArray {
        val signature = if (message.hasSignatureData()) message.getSignatureData() else null
        val gcm = if (signature != null && signature.hasAESGCMResponseData()) {
            signature.getAESGCMResponseData()
        } else {
            null
        }
        if (gcm == null) {
            return if (message.hasProtobufMessageAsBytes()) {
                message.getProtobufMessageAsBytes().toByteArray()
            } else {
                ByteArray(0)
            }
        }
        val from = message.getFromDestination()
        if (from.getSubDestinationCase() != UniversalMessage.Destination.SubDestinationCase.DOMAIN) {
            throw ProtocolException("Encrypted response did not say which domain it came from.")
        }
        val fault = if (message.hasSignedMessageStatus()) {
            message.getSignedMessageStatus().getSignedMessageFault().number
        } else {
            0
        }
        val metadata = SessionCrypto.responseMetadata(
            domain = from.getDomain().number,
            vin = vin,
            counter = gcm.getCounter(),
            flags = message.getFlags(),
            requestHash = requestHash,
            fault = fault,
        )
        val plaintext = SessionCrypto.open(
            sharedKey = sharedKey,
            metadata = metadata,
            nonce = gcm.getNonce().toByteArray(),
            ciphertext = message.getProtobufMessageAsBytes().toByteArray(),
            tag = gcm.getTag().toByteArray(),
        )
        if (!window.accept(gcm.getCounter())) {
            throw ProtocolException("Rejected a replayed response from the car.")
        }
        return plaintext
    }

    /**
     * Applies a signed session-info update carried on an error response.
     * The tag is checked with the existing shared key. A different vehicle
     * public key is rejected, matching Signer.UpdateSessionInfo.
     * Returns true when the session changed.
     */
    fun tryResync(
        message: UniversalMessage.RoutableMessage,
        challenge: ByteArray,
        observedAtUnixSeconds: Long,
    ): Boolean {
        if (!message.hasSessionInfo() || !message.hasSignatureData()) return false
        val signature = message.getSignatureData()
        if (!signature.hasSessionInfoTag()) return false
        val encoded = message.getSessionInfo().toByteArray()
        val tag = signature.getSessionInfoTag().getTag().toByteArray()
        if (!SessionCrypto.sessionInfoTagValid(sharedKey, vin, challenge, encoded, tag)) {
            return false
        }
        val info = try {
            Signatures.SessionInfo.parseFrom(encoded)
        } catch (_: InvalidProtocolBufferException) {
            return false
        }
        if (!info.getPublicKey().toByteArray().contentEquals(vehiclePublicKey)) return false
        val vehicleClock = info.getClockTime().toLong() and 0xFFFF_FFFFL
        val epochChanged = !info.getEpoch().toByteArray().contentEquals(epoch)
        if (!epochChanged && vehicleClock < lastVehicleClock) return false
        if (epochChanged) {
            epoch = info.getEpoch().toByteArray()
            counter = info.getCounter()
        } else if (info.getCounter().toUInt() > counter.toUInt()) {
            counter = info.getCounter()
        }
        lastVehicleClock = vehicleClock
        clockOffsetSeconds = vehicleClock - observedAtUnixSeconds
        return true
    }

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        domain = domain,
        epoch = epoch.copyOf(),
        counter = counter,
        vehiclePublicKey = vehiclePublicKey.copyOf(),
        clockOffsetSeconds = clockOffsetSeconds,
        lastVehicleClock = lastVehicleClock,
    )

    companion object {
        fun open(
            response: UniversalMessage.RoutableMessage,
            vin: String,
            domain: Int,
            requestUuid: ByteArray,
            clientPublicKey: ByteArray,
            observedAtUnixSeconds: Long,
            ecdhX: (ByteArray) -> ByteArray,
        ): VehicleSession {
            if (!response.hasSessionInfo()) {
                throw ProtocolException("The car did not return session info.")
            }
            if (!response.hasSignatureData() || !response.getSignatureData().hasSessionInfoTag()) {
                throw ProtocolException("The car's session info was not authenticated.")
            }
            val encoded = response.getSessionInfo().toByteArray()
            val info = try {
                Signatures.SessionInfo.parseFrom(encoded)
            } catch (error: InvalidProtocolBufferException) {
                throw ProtocolException("The car's session info could not be parsed.", error)
            }
            if (info.getStatus() == Signatures.Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST) {
                throw NotEnrolledException()
            }
            val vehiclePublic = info.getPublicKey().toByteArray()
            if (vehiclePublic.size != 65 || vehiclePublic[0] != 0x04.toByte()) {
                throw ProtocolException("The car sent a public key this watch cannot use.")
            }
            val epoch = info.getEpoch().toByteArray()
            if (epoch.size != 16) {
                throw ProtocolException("The car sent an unexpected session id.")
            }
            val shared = SessionCrypto.sharedKeyFromX(EcPoints.sharedX(ecdhX(vehiclePublic)))
            val tag = response.getSignatureData().getSessionInfoTag().getTag().toByteArray()
            if (!SessionCrypto.sessionInfoTagValid(shared, vin, requestUuid, encoded, tag)) {
                throw ProtocolException("The car's session info failed authentication.")
            }
            val vehicleClock = info.getClockTime().toLong() and 0xFFFF_FFFFL
            return VehicleSession(
                domain = domain,
                vin = vin,
                clientPublicKey = clientPublicKey.copyOf(),
                sharedKey = shared,
                vehiclePublicKey = vehiclePublic,
                epoch = epoch,
                counter = info.getCounter(),
                clockOffsetSeconds = vehicleClock - observedAtUnixSeconds,
                lastVehicleClock = vehicleClock,
            )
        }

        fun restore(
            snapshot: SessionSnapshot,
            vin: String,
            clientPublicKey: ByteArray,
            ecdhX: (ByteArray) -> ByteArray,
        ): VehicleSession {
            val shared = SessionCrypto.sharedKeyFromX(EcPoints.sharedX(ecdhX(snapshot.vehiclePublicKey)))
            return VehicleSession(
                domain = snapshot.domain,
                vin = vin,
                clientPublicKey = clientPublicKey.copyOf(),
                sharedKey = shared,
                vehiclePublicKey = snapshot.vehiclePublicKey.copyOf(),
                epoch = snapshot.epoch.copyOf(),
                counter = snapshot.counter,
                clockOffsetSeconds = snapshot.clockOffsetSeconds,
                lastVehicleClock = snapshot.lastVehicleClock,
            )
        }
    }
}

data class SessionSnapshot(
    val domain: Int,
    val epoch: ByteArray,
    val counter: Int,
    val vehiclePublicKey: ByteArray,
    val clockOffsetSeconds: Long,
    val lastVehicleClock: Long,
)

data class AuthorizedCommand(
    val bytes: ByteArray,
    val requestHash: ByteArray,
    val uuid: ByteArray,
    val routingAddress: ByteArray,
    val counter: Int,
)
