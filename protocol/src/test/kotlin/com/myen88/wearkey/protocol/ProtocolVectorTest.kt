package com.myen88.wearkey.protocol

import com.google.protobuf.ByteString
import com.myen88.wearkey.protocol.ble.Advertisement
import com.myen88.wearkey.protocol.ble.BleFrames
import com.myen88.wearkey.protocol.ble.Reassembler
import com.myen88.wearkey.protocol.crypto.EcPoints
import com.myen88.wearkey.protocol.crypto.Metadata
import com.myen88.wearkey.protocol.crypto.ProtocolException
import com.myen88.wearkey.protocol.crypto.ResponseWindow
import com.myen88.wearkey.protocol.crypto.SessionCrypto
import com.myen88.wearkey.protocol.crypto.VehicleSession
import com.myen88.wearkey.protocol.message.InfotainmentCommands
import com.myen88.wearkey.protocol.message.VcsecCommands
import com.myen88.wearkey.protocol.message.VcsecExpect
import com.myen88.wearkey.protocol.message.VcsecInterpret
import com.myen88.wearkey.protocol.message.VcsecRead
import com.tesla.generated.keys.Keys
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import javax.crypto.KeyAgreement

/**
 * Vectors published in teslamotors/vehicle-command pkg/protocol/protocol.md.
 * These private keys are public test data. Do not enroll them on a vehicle.
 */
class ProtocolVectorTest {
    @Test
    fun advertisementNameMatchesTheSpecification() {
        assertEquals("S1a87a5a75f3df858C", Advertisement.localName("5YJS0000000000000"))
    }

    @Test
    fun sharedKeyMatchesTheSpecification() {
        val secret = ecdh(CLIENT_SCALAR.hexToBytes(), VEHICLE_PUBLIC.hexToBytes())
        val key = SessionCrypto.sharedKeyFromX(EcPoints.sharedX(secret))
        assertEquals("1b2fce19967b79db696f909cff89ea9a", key.toHex())
    }

    @Test
    fun sessionInfoMetadataAndTagMatchTheSpecification() {
        val metadata = Metadata.build {
            add(
                Signatures.Tag.TAG_SIGNATURE_TYPE,
                byteArrayOf(Signatures.SignatureType.SIGNATURE_TYPE_HMAC.number.toByte()),
            )
            add(Signatures.Tag.TAG_PERSONALIZATION, VIN.toByteArray(Charsets.US_ASCII))
            add(Signatures.Tag.TAG_CHALLENGE, CHALLENGE.hexToBytes())
        }
        assertEquals(
            "000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff",
            metadata.toHex(),
        )
        val key = SHARED_KEY.hexToBytes()
        assertEquals(
            "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300",
            SessionCrypto.sessionInfoKey(key).toHex(),
        )
        assertEquals(
            SESSION_TAG,
            SessionCrypto.sessionInfoTag(key, VIN, CHALLENGE.hexToBytes(), SESSION_INFO.hexToBytes()).toHex(),
        )
        val flipped = SESSION_TAG.hexToBytes()
        flipped[0] = (flipped[0].toInt() xor 0x01).toByte()
        assertFalse(
            SessionCrypto.sessionInfoTagValid(key, VIN, CHALLENGE.hexToBytes(), SESSION_INFO.hexToBytes(), flipped),
        )
    }

    @Test
    fun sessionInfoParsesToThePublishedFields() {
        val info = Signatures.SessionInfo.parseFrom(SESSION_INFO.hexToBytes())
        assertEquals(6, info.getCounter())
        assertEquals(2650, info.getClockTime())
        assertEquals("4c463f9cc0d3d26906e982ed224adde6", info.getEpoch().toByteArray().toHex())
        assertEquals(VEHICLE_PUBLIC, info.getPublicKey().toByteArray().toHex())
    }

    @Test
    fun commandMetadataAndAesGcmMatchTheHvacVector() {
        val metadata = SessionCrypto.commandMetadata(
            domain = UniversalMessage.Domain.DOMAIN_INFOTAINMENT.number,
            vin = VIN,
            epoch = EPOCH.hexToBytes(),
            counter = 7,
            expiresAt = 2655,
            flags = TeslaProtocol.FLAG_ENCRYPT_RESPONSE,
        )
        assertEquals(2, TeslaProtocol.FLAG_ENCRYPT_RESPONSE)
        assertEquals(
            "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff",
            metadata.toHex(),
        )
        val sealed = SessionCrypto.seal(
            SHARED_KEY.hexToBytes(),
            metadata,
            NONCE.hexToBytes(),
            HVAC_ON.hexToBytes(),
        )
        assertEquals("38038e8c0f2e", sealed.ciphertext.toHex())
        assertEquals("c228e0ff64991481db3a7bbc133696c5", sealed.tag.toHex())
        assertEquals("05" + sealed.tag.toHex(), sealed.requestHash.toHex())
        val opened = SessionCrypto.open(
            SHARED_KEY.hexToBytes(),
            metadata,
            NONCE.hexToBytes(),
            sealed.ciphertext,
            sealed.tag,
        )
        assertArrayEquals(HVAC_ON.hexToBytes(), opened)
    }

    @Test
    fun handshakeAndEncryptedCommandRoundTripThePublishedKeys() {
        val response = UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(ROUTING.hexToBytes()))
                    .build(),
            )
            .setFromDestination(
                UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
                    .build(),
            )
            .setSessionInfo(ByteString.copyFrom(SESSION_INFO.hexToBytes()))
            .setRequestUuid(ByteString.copyFrom(CHALLENGE.hexToBytes()))
            .setSignatureData(
                Signatures.SignatureData.newBuilder()
                    .setSessionInfoTag(
                        Signatures.HMAC_Signature_Data.newBuilder()
                            .setTag(ByteString.copyFrom(SESSION_TAG.hexToBytes()))
                            .build(),
                    )
                    .build(),
            )
            .build()
        val session = VehicleSession.open(
            response = response,
            vin = VIN,
            domain = UniversalMessage.Domain.DOMAIN_INFOTAINMENT.number,
            requestUuid = CHALLENGE.hexToBytes(),
            clientPublicKey = CLIENT_PUBLIC.hexToBytes(),
            observedAtUnixSeconds = 2650,
            ecdhX = { peer -> ecdh(CLIENT_SCALAR.hexToBytes(), peer) },
        )
        assertEquals(SHARED_KEY, session.sharedKey.toHex())
        assertEquals(6, session.counter)
        val command = session.encrypt(
            plaintext = HVAC_ON.hexToBytes(),
            unixSeconds = 2650,
            routingAddress = ROUTING.hexToBytes(),
            uuid = COMMAND_UUID.hexToBytes(),
            nonce = NONCE.hexToBytes(),
        )
        assertEquals(7, command.counter)
        val parsed = UniversalMessage.RoutableMessage.parseFrom(command.bytes)
        assertEquals(2, parsed.getFlags())
        assertEquals("38038e8c0f2e", parsed.getProtobufMessageAsBytes().toByteArray().toHex())
        val gcm = parsed.getSignatureData().getAESGCMPersonalizedData()
        assertEquals("c228e0ff64991481db3a7bbc133696c5", gcm.getTag().toByteArray().toHex())
        assertEquals(2655, gcm.getExpiresAt())
        assertEquals(CLIENT_PUBLIC, parsed.getSignatureData().getSignerIdentity().getPublicKey().toByteArray().toHex())

        val replyPlain = byteArrayOf(0x01, 0x02, 0x03)
        val replyMetadata = SessionCrypto.responseMetadata(
            domain = UniversalMessage.Domain.DOMAIN_INFOTAINMENT.number,
            vin = VIN,
            counter = 9,
            flags = 0,
            requestHash = command.requestHash,
            fault = 0,
        )
        val replyNonce = "00112233445566778899aabb".hexToBytes()
        val sealedReply = SessionCrypto.seal(session.sharedKey, replyMetadata, replyNonce, replyPlain)
        val reply = UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                UniversalMessage.Destination.newBuilder()
                    .setRoutingAddress(ByteString.copyFrom(ROUTING.hexToBytes()))
                    .build(),
            )
            .setFromDestination(
                UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
                    .build(),
            )
            .setProtobufMessageAsBytes(ByteString.copyFrom(sealedReply.ciphertext))
            .setSignatureData(
                Signatures.SignatureData.newBuilder()
                    .setAESGCMResponseData(
                        Signatures.AES_GCM_Response_Signature_Data.newBuilder()
                            .setNonce(ByteString.copyFrom(replyNonce))
                            .setCounter(9)
                            .setTag(ByteString.copyFrom(sealedReply.tag))
                            .build(),
                    )
                    .build(),
            )
            .build()
        val window = ResponseWindow()
        assertArrayEquals(replyPlain, session.openResponse(reply, command.requestHash, window))
        try {
            session.openResponse(reply, command.requestHash, window)
            throw AssertionError("Replay was accepted")
        } catch (error: ProtocolException) {
            assertTrue(error.message!!.contains("replayed"))
        }
    }

    @Test
    fun badSessionTagIsRejected() {
        val tag = SESSION_TAG.hexToBytes()
        tag[3] = (tag[3].toInt() xor 0x80).toByte()
        val response = UniversalMessage.RoutableMessage.newBuilder()
            .setSessionInfo(ByteString.copyFrom(SESSION_INFO.hexToBytes()))
            .setSignatureData(
                Signatures.SignatureData.newBuilder()
                    .setSessionInfoTag(
                        Signatures.HMAC_Signature_Data.newBuilder()
                            .setTag(ByteString.copyFrom(tag))
                            .build(),
                    )
                    .build(),
            )
            .build()
        try {
            VehicleSession.open(
                response = response,
                vin = VIN,
                domain = 3,
                requestUuid = CHALLENGE.hexToBytes(),
                clientPublicKey = CLIENT_PUBLIC.hexToBytes(),
                observedAtUnixSeconds = 2650,
                ecdhX = { peer -> ecdh(CLIENT_SCALAR.hexToBytes(), peer) },
            )
            throw AssertionError("Bad tag was accepted")
        } catch (error: ProtocolException) {
            assertTrue(error.message!!.contains("authentication"))
        }
    }

    @Test
    fun hvacAndChargePayloadsEncodeWithTheOfficialSchema() {
        assertEquals("120452020801", InfotainmentCommands.climate(on = true).toHex())
        val off = CarAction.climateOff()
        assertFalse(off.getVehicleAction().getHvacAutoAction().getPowerOn())
        val charge = InfotainmentCommands.chargeState()
        val parsed = com.tesla.generated.carserver.server.CarServer.Action.parseFrom(charge)
        assertEquals(
            com.tesla.generated.carserver.server.CarServer.VehicleAction.VehicleActionMsgCase.GETVEHICLEDATA,
            parsed.getVehicleAction().getVehicleActionMsgCase(),
        )
    }

    @Test
    fun vcsecPayloadsRoundTripIncludingDefaultUnlock() {
        val unlock = Vcsec.UnsignedMessage.parseFrom(
            VcsecCommands.rke(Vcsec.RKEAction_E.RKE_ACTION_UNLOCK),
        )
        assertEquals(Vcsec.RKEAction_E.RKE_ACTION_UNLOCK, unlock.getRKEAction())
        assertNotEquals(0, unlock.toByteArray().size)

        val lock = Vcsec.UnsignedMessage.parseFrom(
            VcsecCommands.rke(Vcsec.RKEAction_E.RKE_ACTION_LOCK),
        )
        assertEquals(Vcsec.RKEAction_E.RKE_ACTION_LOCK, lock.getRKEAction())

        val trunk = Vcsec.UnsignedMessage.parseFrom(VcsecCommands.openTrunk())
        assertEquals(
            Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN,
            trunk.getClosureMoveRequest().getRearTrunk(),
        )
        val close = Vcsec.UnsignedMessage.parseFrom(VcsecCommands.closeTrunk())
        assertEquals(
            Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE,
            close.getClosureMoveRequest().getRearTrunk(),
        )
        assertEquals(
            com.myen88.wearkey.protocol.message.RearTrunk.OPEN,
            com.myen88.wearkey.protocol.message.VcsecInterpret.rearTrunk(
                Vcsec.ClosureState_E.CLOSURESTATE_OPEN,
            ),
        )
        assertEquals(
            com.myen88.wearkey.protocol.message.RearTrunk.CLOSED,
            com.myen88.wearkey.protocol.message.VcsecInterpret.rearTrunk(
                Vcsec.ClosureState_E.CLOSURESTATE_CLOSED,
            ),
        )
        assertEquals(
            com.myen88.wearkey.protocol.message.RearTrunk.UNKNOWN,
            com.myen88.wearkey.protocol.message.VcsecInterpret.rearTrunk(
                Vcsec.ClosureState_E.CLOSURESTATE_UNKNOWN,
            ),
        )
        val added = Vcsec.UnsignedMessage.parseFrom(
            VcsecCommands.addKey(CLIENT_PUBLIC.hexToBytes(), Keys.Role.ROLE_OWNER, Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE),
        )
        val change = added.getWhitelistOperation().getAddKeyToWhitelistAndAddPermissions()
        assertEquals(CLIENT_PUBLIC, change.getKey().getPublicKeyRaw().toByteArray().toHex())
        assertEquals(Keys.Role.ROLE_OWNER, change.getKeyRole())
        assertEquals(
            Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE,
            added.getWhitelistOperation().getMetadataForKey().getKeyFormFactor(),
        )
    }

    @Test
    fun whitelistAndRkeResponsesAreClassified() {
        val waiting = Vcsec.FromVCSECMessage.newBuilder()
            .setCommandStatus(
                Vcsec.CommandStatus.newBuilder()
                    .setOperationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT)
                    .build(),
            )
            .build()
        val waitRead = VcsecInterpret.read(waiting.toByteArray(), VcsecExpect.WHITELIST)
        assertTrue(waitRead is VcsecRead.Waiting && waitRead.forCard)

        val enrolled = Vcsec.FromVCSECMessage.newBuilder()
            .setCommandStatus(
                Vcsec.CommandStatus.newBuilder()
                    .setWhitelistOperationStatus(
                        Vcsec.WhitelistOperation_status.newBuilder()
                            .setWhitelistOperationInformation(
                                Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE,
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        assertTrue(VcsecInterpret.read(enrolled.toByteArray(), VcsecExpect.WHITELIST) is VcsecRead.Success)

        val timeout = Vcsec.FromVCSECMessage.newBuilder()
            .setCommandStatus(
                Vcsec.CommandStatus.newBuilder()
                    .setWhitelistOperationStatus(
                        Vcsec.WhitelistOperation_status.newBuilder()
                            .setWhitelistOperationInformation(
                                Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_TAP,
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        val failed = VcsecInterpret.read(timeout.toByteArray(), VcsecExpect.WHITELIST)
        assertTrue(failed is VcsecRead.Failure)
        assertTrue((failed as VcsecRead.Failure).message.contains("key card"))

        val legacyError = Vcsec.FromVCSECMessage.newBuilder()
            .setCommandStatus(
                Vcsec.CommandStatus.newBuilder()
                    .setOperationStatus(Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR)
                    .build(),
            )
            .build()
        assertTrue(VcsecInterpret.read(legacyError.toByteArray(), VcsecExpect.RKE) is VcsecRead.Ignore)

        val empty = Vcsec.FromVCSECMessage.newBuilder().build()
        assertTrue(VcsecInterpret.read(empty.toByteArray(), VcsecExpect.RKE) is VcsecRead.Success)

        val locked = Vcsec.FromVCSECMessage.newBuilder()
            .setVehicleStatus(
                Vcsec.VehicleStatus.newBuilder()
                    .setVehicleLockState(Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED)
                    .setVehicleSleepStatus(Vcsec.VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)
                    .setClosureStatuses(
                        Vcsec.ClosureStatuses.newBuilder()
                            .setRearTrunk(Vcsec.ClosureState_E.CLOSURESTATE_CLOSED)
                            .setFrontTrunk(Vcsec.ClosureState_E.CLOSURESTATE_OPEN)
                            .build(),
                    )
                    .build(),
            )
            .build()
        val status = VcsecInterpret.read(locked.toByteArray(), VcsecExpect.STATUS) as VcsecRead.Success
        assertEquals("Locked", status.lock!!.label)
        assertEquals(true, status.lock.locked)
        assertEquals(true, status.lock.asleep)
        assertFalse(status.lock.trunkOpen)
        assertTrue(status.lock.frunkOpen)
    }

    @Test
    fun bleFramesSurviveArbitraryChunking() {
        val payload = "hello tesla".toByteArray()
        val framed = BleFrames.encode(payload)
        assertEquals(payload.size, ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF))
        val second = BleFrames.encode(byteArrayOf(1, 2, 3, 4))
        val both = framed + second
        val reassembler = Reassembler()
        val out = ArrayList<ByteArray>()
        for (chunk in BleFrames.chunks(both, chunkSize = 3)) {
            out.addAll(reassembler.push(chunk))
        }
        assertEquals(2, out.size)
        assertArrayEquals(payload, out[0])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out[1])
    }

    @Test
    fun responseWindowRejectsReplaysAndAcceptsALateNeighbor() {
        val window = ResponseWindow()
        assertTrue(window.accept(5))
        assertFalse(window.accept(5))
        assertTrue(window.accept(7))
        assertTrue(window.accept(6))
        assertFalse(window.accept(6))
        assertTrue(window.accept(40))
        assertFalse(window.accept(5))
    }

    @Test
    fun vinCheckDigitRejectsTypos() {
        assertTrue(Vin.isValid("1M8GDM9AXKP042788"))
        assertTrue(Vin.isValid("5yjsa1e24mf000001"))
        assertNull(Vin.validationError("5YJSA1E24MF000001"))
        assertFalse(Vin.isValid("5YJSA1E26MF000001"))
        assertFalse(Vin.isValid("5YJSA1E24MF00000I"))
        assertTrue(Vin.validationError("short")!!.contains("17"))
    }

    @Test
    fun publicPointRoundTrips() {
        val key = EcPoints.toPublicKey(CLIENT_PUBLIC.hexToBytes()) as ECPublicKey
        assertEquals(CLIENT_PUBLIC, EcPoints.encode(key).toHex())
    }

    private fun ecdh(scalar: ByteArray, peerUncompressed: ByteArray): ByteArray {
        val peer = EcPoints.toPublicKey(peerUncompressed) as ECPublicKey
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            ECPrivateKeySpec(BigInteger(1, scalar), peer.params),
        )
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        return agreement.generateSecret()
    }

    private object CarAction {
        fun climateOff() = com.tesla.generated.carserver.server.CarServer.Action.parseFrom(
            InfotainmentCommands.climate(on = false),
        )
    }

    private companion object {
        const val VIN = "5YJ30123456789ABC"
        const val CLIENT_SCALAR = "2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB"
        const val CLIENT_PUBLIC =
            "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
                "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e"
        const val VEHICLE_PUBLIC =
            "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97"
        const val SHARED_KEY = "1b2fce19967b79db696f909cff89ea9a"
        const val CHALLENGE = "1588d5a30eabc6f8fc9a951b11f6fd11"
        const val SESSION_INFO =
            "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97" +
                "1a104c463f9cc0d3d26906e982ed224adde6255a0a0000"
        const val SESSION_TAG = "996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002"
        const val EPOCH = "4c463f9cc0d3d26906e982ed224adde6"
        const val NONCE = "dbf79447fa156674dae1caed"
        const val HVAC_ON = "120452020801"
        const val ROUTING = "2c907bd76c640d360b3027dc7404efde"
        const val COMMAND_UUID = "58406580528b6a5301391800b4fe9b99"
    }
}
