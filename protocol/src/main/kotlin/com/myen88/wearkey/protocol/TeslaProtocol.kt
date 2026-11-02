package com.myen88.wearkey.protocol

import com.tesla.generated.universalmessage.UniversalMessage

/**
 * Identifiers and constants from Tesla's vehicle-command specification.
 * The flag value is a bit mask: `1 << FLAG_ENCRYPT_RESPONSE`, which is 2.
 */
object TeslaProtocol {
    const val SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e"
    const val WRITE_CHARACTERISTIC_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e"
    const val READ_CHARACTERISTIC_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e"
    const val CLIENT_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val ADDRESS_LENGTH = 16
    const val UUID_LENGTH = 16

    /** Official client default. See dispatcher/session.go `defaultExpiration`. */
    const val COMMAND_LIFETIME_SECONDS = 5

    val FLAG_ENCRYPT_RESPONSE: Int =
        1 shl UniversalMessage.Flags.FLAG_ENCRYPT_RESPONSE.number

    val VEHICLE_SECURITY: UniversalMessage.Domain =
        UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY

    val INFOTAINMENT: UniversalMessage.Domain =
        UniversalMessage.Domain.DOMAIN_INFOTAINMENT
}
