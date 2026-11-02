package com.myen88.wearkey.protocol

/**
 * ISO 3779 VIN checks. A wrong check digit almost always means a typo, and a
 * typo produces the wrong BLE advertisement name, so the watch would scan forever.
 */
object Vin {
    private val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    fun normalize(raw: String): String =
        raw.trim().uppercase().replace(" ", "").replace("-", "")

    fun isValid(raw: String): Boolean {
        val vin = normalize(raw)
        if (vin.length != 17) return false
        if (!vin.all { isVinChar(it) }) return false
        return checkDigit(vin) == vin[8]
    }

    fun validationError(raw: String): String? {
        val vin = normalize(raw)
        if (vin.isEmpty()) return "Enter the 17-character VIN."
        if (vin.length != 17) return "A VIN is 17 characters."
        if (!vin.all { isVinChar(it) }) return "VINs do not use I, O, or Q."
        if (checkDigit(vin) != vin[8]) return "That check digit does not match. Look again at the VIN."
        return null
    }

    fun checkDigit(vin: String): Char {
        var sum = 0
        for (index in vin.indices) {
            sum += transliterate(vin[index]) * weights[index]
        }
        val remainder = sum % 11
        return if (remainder == 10) 'X' else ('0' + remainder)
    }

    private fun isVinChar(character: Char): Boolean =
        character.isDigit() || (character in 'A'..'Z' && character != 'I' && character != 'O' && character != 'Q')

    private fun transliterate(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        'A', 'J' -> 1
        'B', 'K', 'S' -> 2
        'C', 'L', 'T' -> 3
        'D', 'M', 'U' -> 4
        'E', 'N', 'V' -> 5
        'F', 'W' -> 6
        'G', 'P', 'X' -> 7
        'H', 'Y' -> 8
        'R', 'Z' -> 9
        else -> throw IllegalArgumentException("Invalid VIN character $character")
    }
}
