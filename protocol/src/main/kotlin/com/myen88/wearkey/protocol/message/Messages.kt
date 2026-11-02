package com.myen88.wearkey.protocol.message

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.myen88.wearkey.protocol.TeslaProtocol
import com.myen88.wearkey.protocol.crypto.ProtocolException
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.errors.Errors
import com.tesla.generated.keys.Keys
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec

object RoutableMessages {
    fun unsigned(
        domain: UniversalMessage.Domain,
        payload: ByteArray,
        routingAddress: ByteArray,
        uuid: ByteArray,
        flags: Int = TeslaProtocol.FLAG_ENCRYPT_RESPONSE,
    ): ByteArray = messageBuilder(domain, routingAddress, uuid, flags)
        .setProtobufMessageAsBytes(ByteString.copyFrom(payload))
        .build()
        .toByteArray()

    /** Handshake requests do not set FLAG_ENCRYPT_RESPONSE. The official client leaves flags at 0. */
    fun sessionInfoRequest(
        domain: UniversalMessage.Domain,
        clientPublicKey: ByteArray,
        routingAddress: ByteArray,
        uuid: ByteArray,
    ): ByteArray = messageBuilder(domain, routingAddress, uuid, flags = 0)
        .setSessionInfoRequest(
            UniversalMessage.SessionInfoRequest.newBuilder()
                .setPublicKey(ByteString.copyFrom(clientPublicKey))
                .build(),
        )
        .build()
        .toByteArray()

    fun authorized(
        domain: Int,
        routingAddress: ByteArray,
        uuid: ByteArray,
        clientPublicKey: ByteArray,
        epoch: ByteArray,
        nonce: ByteArray,
        counter: Int,
        expiresAt: Long,
        tag: ByteArray,
        ciphertext: ByteArray,
        flags: Int,
    ): ByteArray {
        val domainEnum = UniversalMessage.Domain.forNumber(domain)
            ?: throw ProtocolException("Unknown vehicle domain $domain.")
        return messageBuilder(domainEnum, routingAddress, uuid, flags)
            .setProtobufMessageAsBytes(ByteString.copyFrom(ciphertext))
            .setSignatureData(
                Signatures.SignatureData.newBuilder()
                    .setSignerIdentity(
                        Signatures.KeyIdentity.newBuilder()
                            .setPublicKey(ByteString.copyFrom(clientPublicKey))
                            .build(),
                    )
                    .setAESGCMPersonalizedData(
                        Signatures.AES_GCM_Personalized_Signature_Data.newBuilder()
                            .setEpoch(ByteString.copyFrom(epoch))
                            .setNonce(ByteString.copyFrom(nonce))
                            .setCounter(counter)
                            .setExpiresAt(expiresAt.toInt())
                            .setTag(ByteString.copyFrom(tag))
                            .build(),
                    )
                    .build(),
            )
            .build()
            .toByteArray()
    }

    fun parse(bytes: ByteArray): UniversalMessage.RoutableMessage? = try {
        UniversalMessage.RoutableMessage.parseFrom(bytes)
    } catch (_: InvalidProtocolBufferException) {
        null
    }

    fun matches(message: UniversalMessage.RoutableMessage, routingAddress: ByteArray, uuid: ByteArray): Boolean {
        val to = message.getToDestination() ?: return false
        if (to.getSubDestinationCase() != UniversalMessage.Destination.SubDestinationCase.ROUTING_ADDRESS) {
            return false
        }
        if (!to.getRoutingAddress().toByteArray().contentEquals(routingAddress)) return false
        val echoed = message.getRequestUuid().toByteArray()
        if (echoed.isNotEmpty() && !echoed.contentEquals(uuid)) return false
        return true
    }

    private fun messageBuilder(
        domain: UniversalMessage.Domain,
        routingAddress: ByteArray,
        uuid: ByteArray,
        flags: Int,
    ): UniversalMessage.RoutableMessage.Builder {
        val builder = UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                UniversalMessage.Destination.newBuilder().setDomain(domain).build(),
            )
            .setFromDestination(
                UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(routingAddress))
                    .build(),
            )
            .setUuid(ByteString.copyFrom(uuid))
        if (flags != 0) builder.setFlags(flags)
        return builder
    }
}

object VcsecCommands {
    fun status(): ByteArray = Vcsec.UnsignedMessage.newBuilder()
        .setInformationRequest(
            Vcsec.InformationRequest.newBuilder()
                .setInformationRequestType(
                    Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
                )
                .build(),
        )
        .build()
        .toByteArray()

    fun rke(action: Vcsec.RKEAction_E): ByteArray = Vcsec.UnsignedMessage.newBuilder()
        .setRKEAction(action)
        .build()
        .toByteArray()

    fun openTrunk(): ByteArray = closure { setRearTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN) }

    fun openFrunk(): ByteArray = closure { setFrontTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN) }

    fun addKey(
        publicKey: ByteArray,
        role: Keys.Role = Keys.Role.ROLE_OWNER,
        formFactor: Vcsec.KeyFormFactor = Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE,
    ): ByteArray = Vcsec.UnsignedMessage.newBuilder()
        .setWhitelistOperation(
            Vcsec.WhitelistOperation.newBuilder()
                .setAddKeyToWhitelistAndAddPermissions(
                    Vcsec.PermissionChange.newBuilder()
                        .setKey(
                            Vcsec.PublicKey.newBuilder()
                                .setPublicKeyRaw(ByteString.copyFrom(publicKey))
                                .build(),
                        )
                        .setKeyRole(role)
                        .build(),
                )
                .setMetadataForKey(
                    Vcsec.KeyMetadata.newBuilder()
                        .setKeyFormFactor(formFactor)
                        .build(),
                )
                .build(),
        )
        .build()
        .toByteArray()

    private fun closure(fill: Vcsec.ClosureMoveRequest.Builder.() -> Unit): ByteArray =
        Vcsec.UnsignedMessage.newBuilder()
            .setClosureMoveRequest(Vcsec.ClosureMoveRequest.newBuilder().apply(fill).build())
            .build()
            .toByteArray()
}

object InfotainmentCommands {
    fun climate(on: Boolean): ByteArray = CarServer.Action.newBuilder()
        .setVehicleAction(
            CarServer.VehicleAction.newBuilder()
                .setHvacAutoAction(
                    CarServer.HvacAutoAction.newBuilder().setPowerOn(on).build(),
                )
                .build(),
        )
        .build()
        .toByteArray()

    fun chargeState(): ByteArray = CarServer.Action.newBuilder()
        .setVehicleAction(
            CarServer.VehicleAction.newBuilder()
                .setGetVehicleData(
                    CarServer.GetVehicleData.newBuilder()
                        .setGetChargeState(CarServer.GetChargeState.getDefaultInstance())
                        .build(),
                )
                .build(),
        )
        .build()
        .toByteArray()

    fun parseResponse(body: ByteArray): CarServer.Response? = try {
        CarServer.Response.parseFrom(body)
    } catch (_: InvalidProtocolBufferException) {
        null
    }

    fun requireOk(response: CarServer.Response) {
        if (!response.hasActionStatus()) return
        val status = response.getActionStatus()
        if (status.getResult() == CarServer.OperationStatus_E.OPERATIONSTATUS_ERROR) {
            val reason = if (status.hasResultReason()) status.getResultReason().getPlainText() else ""
            throw ProtocolException(reason.ifBlank { "The car rejected the command." })
        }
    }

    fun batteryPercent(response: CarServer.Response): Int? {
        if (!response.hasVehicleData()) return null
        val charge: Vehicle.ChargeState = response.getVehicleData().getChargeState()
        return when {
            charge.hasBatteryLevel() -> charge.getBatteryLevel()
            charge.hasUsableBatteryLevel() -> charge.getUsableBatteryLevel()
            else -> null
        }
    }
}

enum class VcsecExpect { WHITELIST, RKE, STATUS }

sealed class VcsecRead {
    data class Success(val lock: LockSnapshot?, val detail: String? = null) : VcsecRead()
    data class Waiting(val forCard: Boolean) : VcsecRead()
    data class Failure(val message: String) : VcsecRead()
    data object Ignore : VcsecRead()
}

data class LockSnapshot(
    val label: String,
    val locked: Boolean?,
    val trunkOpen: Boolean,
    val frunkOpen: Boolean,
    val asleep: Boolean?,
)

object VcsecInterpret {
    fun parse(body: ByteArray): Vcsec.FromVCSECMessage? = try {
        Vcsec.FromVCSECMessage.parseFrom(body)
    } catch (_: InvalidProtocolBufferException) {
        null
    }

    fun read(body: ByteArray, expect: VcsecExpect): VcsecRead {
        val message = parse(body) ?: return VcsecRead.Ignore
        if (message.hasNominalError()) {
            return VcsecRead.Failure(nominalMessage(message.getNominalError()))
        }
        if (expect == VcsecExpect.WHITELIST && message.hasCommandStatus()) {
            val status = message.getCommandStatus()
            if (status.hasWhitelistOperationStatus()) {
                return whitelistTerminal(status.getWhitelistOperationStatus())
            }
            if (status.getOperationStatus() == Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT) {
                return VcsecRead.Waiting(forCard = true)
            }
            return VcsecRead.Ignore
        }
        if (expect == VcsecExpect.STATUS && message.hasVehicleStatus()) {
            return VcsecRead.Success(lockSnapshot(message.getVehicleStatus()))
        }
        if (message.hasCommandStatus()) {
            val status = message.getCommandStatus()
            if (status.getOperationStatus() == Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT) {
                return VcsecRead.Waiting(forCard = false)
            }
            if (status.hasSignedMessageStatus()) {
                val info = status.getSignedMessageStatus().getSignedMessageInformation()
                if (info != Vcsec.SignedMessage_information_E.SIGNEDMESSAGE_INFORMATION_NONE) {
                    return VcsecRead.Failure(signedMessage(info))
                }
            }
            // OPERATIONSTATUS_ERROR without a specific code is legacy. Keep waiting.
            return VcsecRead.Ignore
        }
        if (expect == VcsecExpect.RKE) {
            val lock = if (message.hasVehicleStatus()) lockSnapshot(message.getVehicleStatus()) else null
            return VcsecRead.Success(lock)
        }
        return VcsecRead.Ignore
    }

    private fun whitelistTerminal(status: Vcsec.WhitelistOperation_status): VcsecRead {
        val code = status.getWhitelistOperationInformation()
        if (code == Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE) {
            return VcsecRead.Success(lock = null, detail = "The car enrolled this key.")
        }
        if (code == Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_ATTEMPTING_TO_ADD_KEY_THAT_IS_ALREADY_ON_THE_WHITELIST) {
            return VcsecRead.Success(lock = null, detail = "This key is already on the car.")
        }
        return VcsecRead.Failure(whitelistMessage(code))
    }

    fun whitelistMessage(code: Vcsec.WhitelistOperation_information_E): String = when (code) {
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_TAP ->
            "Timed out waiting for the key card. Hold it on the center console and try again."
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_UI_ACK ->
            "Timed out waiting for confirmation on the car screen."
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_UI_DENIED ->
            "The request was denied on the car screen."
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_CANCELLED ->
            "Pairing was cancelled on the car."
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_VALET_MODE ->
            "Exit valet mode on the car and try again."
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL,
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_KEYCHAIN_WHILE_FS_FULL,
        -> "The car's key list is full. Remove a key, then try again."
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_INVALID_PUBLIC_KEY ->
            "The car rejected the public key."
        else -> "Pairing failed (${code.name.removePrefix("WHITELISTOPERATION_INFORMATION_")})."
    }

    fun lockSnapshot(status: Vcsec.VehicleStatus): LockSnapshot {
        val closures = status.getClosureStatuses()
        return LockSnapshot(
            label = lockLabel(status.getVehicleLockState()),
            locked = when (status.getVehicleLockState()) {
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED,
                -> true
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
                Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED,
                -> false
                else -> null
            },
            trunkOpen = isOpen(closures.getRearTrunk()),
            frunkOpen = isOpen(closures.getFrontTrunk()),
            asleep = when (status.getVehicleSleepStatus()) {
                Vcsec.VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP -> true
                Vcsec.VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE -> false
                else -> null
            },
        )
    }

    private fun lockLabel(state: Vcsec.VehicleLockState_E): String = when (state) {
        Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED -> "Locked"
        Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED -> "Unlocked"
        Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED -> "Locked inside"
        Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED -> "Selective unlock"
        else -> "Lock unknown"
    }

    private fun isOpen(state: Vcsec.ClosureState_E): Boolean = when (state) {
        Vcsec.ClosureState_E.CLOSURESTATE_OPEN,
        Vcsec.ClosureState_E.CLOSURESTATE_AJAR,
        Vcsec.ClosureState_E.CLOSURESTATE_OPENING,
        Vcsec.ClosureState_E.CLOSURESTATE_FAILED_UNLATCH,
        -> true
        else -> false
    }

    private fun nominalMessage(error: Errors.NominalError): String = when (error.getGenericError()) {
        Errors.GenericError_E.GENERICERROR_CLOSURES_OPEN -> "A door or trunk is open."
        Errors.GenericError_E.GENERICERROR_ALREADY_ON -> "That is already on."
        Errors.GenericError_E.GENERICERROR_DISABLED_FOR_USER_COMMAND -> "The car will not run that command right now."
        Errors.GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK -> "The car is not in Park."
        Errors.GenericError_E.GENERICERROR_UNAUTHORIZED -> "This key is not allowed to do that."
        Errors.GenericError_E.GENERICERROR_NOT_ALLOWED_OVER_TRANSPORT -> "The car refused that command over Bluetooth."
        else -> "The car returned an error (${error.getGenericError().name.removePrefix("GENERICERROR_")})."
    }

    private fun signedMessage(info: Vcsec.SignedMessage_information_E): String =
        "The car rejected the signed command (${info.name.removePrefix("SIGNEDMESSAGE_INFORMATION_")})."
}

object FaultText {
    fun message(fault: UniversalMessage.MessageFault_E): String = when (fault) {
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_NONE -> "Ok"
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_BUSY -> "The car is busy. Try again."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT -> "A vehicle system timed out. Try again."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID ->
            "The car does not recognize this key. Pair it with your key card."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INACTIVE_KEY -> "This key is disabled on the car."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE ->
            "The car rejected the command authentication. Reconnecting."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER ->
            "The command counter was out of date. Reconnecting."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES ->
            "This key is not allowed to do that."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_DOMAINS ->
            "The car did not understand which system the command was for."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_COMMAND ->
            "The car does not support that command."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_DECODING -> "The car could not parse the command."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL -> "The car hit an internal error. Try again."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_WRONG_PERSONALIZATION ->
            "That command was addressed to a different VIN."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_KEYCHAIN_IS_FULL ->
            "The car's key list is full."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH ->
            "The car restarted its session. Reconnecting."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED ->
            "The command expired before the car accepted it. Try again."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG ->
            "The car rejected the command lifetime."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_REPEATED_COUNTER ->
            "The car already saw that command counter. Reconnecting."
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_REMOTE_ACCESS_DISABLED ->
            "Mobile access is disabled on the car."
        else -> "The car returned ${fault.name.removePrefix("MESSAGEFAULT_ERROR_")}."
    }

    fun isSessionFault(fault: UniversalMessage.MessageFault_E): Boolean = when (fault) {
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
        UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_REPEATED_COUNTER,
        -> true
        else -> false
    }

    fun isBusy(fault: UniversalMessage.MessageFault_E): Boolean =
        fault == UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_BUSY

    fun faultOf(message: UniversalMessage.RoutableMessage): UniversalMessage.MessageFault_E? {
        if (!message.hasSignedMessageStatus()) return null
        val fault = message.getSignedMessageStatus().getSignedMessageFault()
        return if (fault == UniversalMessage.MessageFault_E.MESSAGEFAULT_ERROR_NONE) null else fault
    }
}
