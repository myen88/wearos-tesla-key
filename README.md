# Watch Key

A standalone Wear OS app that acts as a Bluetooth Low Energy key for a Tesla. The watch generates its own key, the car enrolls it after you tap an existing NFC key card, and the watch can then lock, unlock, open the trunk or frunk, and try climate and battery commands without the phone.

It speaks Tesla's open [vehicle-command](https://github.com/teslamotors/vehicle-command) protocol (vendored at commit `d6239bf377b8a5859e8f5c9d64409df2a057934a`). There is no Tesla account login and no Fleet API proxy. The watch talks to the car over BLE.

Target device: Pixel Watch 2 (Wear OS 4 / 5, Android 13 / 14). `minSdk` is 33, so newer Pixel Watches can install the same APK.

## What works in this build

The debug APK builds with `./gradlew assembleDebug`. The protocol tests pass without a car. They check the published test vectors in Tesla's protocol specification: BLE advertisement name, ECDH shared key, session-info HMAC, AES-GCM command encryption, protobuf layouts for lock / unlock / trunk / HVAC / charge state, VCSEC response classification, and BLE framing.

These parts are implemented and can only be confirmed on a real car, in range, with a key card:

- The car actually advertising `S` + SHA-1(VIN)[:8] + `C` and accepting the GATT service.
- Enrollment: unsigned whitelist add, card tap on the center console, confirm on the car screen.
- Authenticated lock, unlock, trunk, and frunk on VCSEC.
- Lock-state reporting.
- Climate on/off and battery percent on the infotainment domain. That domain sleeps with the car. The app sends a VCSEC wake and retries once. Older firmware (before 2024.38) ignores `FLAG_ENCRYPT_RESPONSE` and replies in plaintext; the app accepts that.
- The tile and the complication launching the app and sending the command.
- Android Keystore ECDH on the watch. The unit tests use Java's ECDH provider, not the watch keystore.

Pre-2021 Model S and Model X do not use this protocol.

## Architecture

```
Tile / complication / main UI
        |
WatchKeyViewModel          AndroidKeystoreKey (P-256, non-exportable)
        |                  WatchKeyStore (VIN, enrollment, session cache)
VehicleCommander
        |
TeslaBleClient  -- scan, connect, write-with-response, notify, disconnect
        |
:protocol
  advertisement name, 2-byte BLE frames
  session crypto (ECDH, HMAC, AES-GCM)
  official .proto files
```

`:protocol` is a plain JVM library. `:app` is the Wear OS application. The watch is the BLE central. The car is the peripheral.

| Piece | Role |
| --- | --- |
| BLE service `00000211-b2d1-43f0-9b88-960cebf8b91e` | Vehicle command channel |
| Write `00000212-…` | Client to car, write with response |
| Notify `00000213-…` | Car to client |
| Local name | `S` + lowercase hex of the first 8 bytes of SHA-1(VIN) + `C` |
| VCSEC (`DOMAIN_VEHICLE_SECURITY`) | Lock, unlock, trunk, frunk, wake, key whitelist, closure status. Replies while the car is asleep. |
| Infotainment (`DOMAIN_INFOTAINMENT`) | Climate and charge state. Separate handshake. Asleep when the car is asleep. |

Each BLE message is a protobuf `RoutableMessage` with a 2-byte big-endian length prefix. Writes are split to `MTU - 3`. A fresh 16-byte routing address is used for every VCSEC command, because VCSEC often does not echo the request UUID. Infotainment reuses one routing address for the connection and responses are matched on the UUID as well.

The session key is `SHA-1(ECDH X coordinate)[:16]`. Commands are AES-GCM with a 5-second lifetime (the official client's default), an incrementing counter, and `FLAG_ENCRYPT_RESPONSE`. The counter is saved before the response comes back so a lost reply cannot reuse it. A session-counter or epoch error triggers one resync or a new handshake, then one retry.

VCSEC can emit several replies for one request. A whitelist operation is finished when `whitelistOperationStatus` is set. `OPERATIONSTATUS_WAIT` during pairing means the car is waiting for the card. For lock and trunk, an empty VCSEC message means success. `OPERATIONSTATUS_ERROR` with no specific code is ignored, matching the spec for legacy clients.

The app connects, runs the command, and disconnects. It does not hold one of VCSEC's three BLE slots.

## Pairing

1. On the watch, enter the VIN. The check digit is validated so a typo does not scan for the wrong advertisement.
2. The watch creates an EC P-256 key in Android Keystore (`PURPOSE_AGREE_KEY`, alias `tesla-watch-key-p256`). StrongBox is requested and, if the watch does not have it, the key falls back to the TEE. The private key's encoding is null on a real device; the UI says so.
3. Stand next to the car and start pairing. The watch sends an unsigned `addKeyToWhitelistAndAddPermissions` to VCSEC. The default role is **Owner** and the default form factor is **Android device**, so the car can list it with phone keys. A cloud-key form factor is on the pairing screen if you need the same shape `tesla-control add-key-request` uses. Fleet Manager keys cannot authorize BLE commands.
4. When the watch says so, tap an existing key card on the center console and confirm on the car screen. You have roughly half a minute.
5. The watch completes a VCSEC handshake. If the car reports the key is not on the whitelist, pairing failed. "Already on the whitelist" is treated as success.

The fingerprint on the watch is the first 8 bytes of SHA-1 of the uncompressed public key. The car's key list uses that hash. Removing the app does not remove the key from the car. Delete it under Controls > Locks.

## Controls

- Unlock, lock.
- Open trunk, open frunk. The second tap sends the command, so a stray tap does not pop the trunk. The command is `CLOSURE_MOVE_TYPE_OPEN`, not a toggle.
- Climate on and climate off (infotainment).
- Refresh reads VCSEC closure status and, if infotainment answers, battery percent (`battery_level`, otherwise `usable_battery_level`).
- The Watch Key tile has Unlock and Lock. The Unlock complication does the same. Both open this app and send the command. That is one tap, and the watch has to be in BLE range. It is not walk-up unlock.

## Walk-up unlock

Not implemented, and not something this app can honestly promise.

Tesla's phone key, per the owner's manual, is a presence key: the car sees the phone over Bluetooth, the doors unlock when you pull a handle, and walk-away lock can lock the car as you leave. That presence protocol is not the authenticated command channel documented in vehicle-command.

What the published spec does define is the flow this app uses: the watch scans for the car, connects as a GATT client, handshakes, and sends an authenticated command. Tesla engineers have said the car also emits short messages used for passive phone-key authentication, and they did not publish that format (vehicle-command issue 390). Guessing an advertisement and hoping the car treats the watch as a phone key would be unreliable and could interfere with the real phone key.

Wear OS makes a background imitation even less attractive:

- Google has tightened background execution. Shipping Wear apps have dropped always-on Bluetooth modes because a long-running background process is no longer allowed. The supported shape is "open the app, or tap a tile, then connect."
- A foreground service can keep a radio up for a few seconds. Leaving one running so the watch is always connected fights the VCSEC limit of three simultaneous links (phone key, key fobs, and this watch) and will drain a Pixel Watch.
- Background advertising as a BLE peripheral is restricted as well, and the bytes the car expects are not in the spec.

So the watch will not unlock the car as you walk up, and it will not walk-away lock. Use the app, the tile, or the complication when you are next to the car.

## Security

- The private key is generated on the watch and is not uploaded anywhere. Commands are signed with ECDH plus AES-GCM. There is no OAuth token in this app.
- Enrollment needs a physical key card and a confirmation on the car screen. A nearby attacker who only has this APK cannot add themselves.
- The key's role is Owner, so it can authorize the same class of commands as your phone key, including key management. If the watch is lost or stolen, delete this key on the car screen. Uninstalling the app is not enough. A watch PIN is the practical protection: the keystore key is not gated on a biometric, because Pixel Watch 2 has no fingerprint sensor and a prompt would break the tile.
- The VIN and the session cache (epoch, counter, vehicle public key, clock offset) stay in app-private storage. The shared AES key is derived again with the keystore when needed and is not written to disk. `android:allowBackup` is false.
- Debug builds are signed with the local Android debug keystore, which is not in this repo. Logcat tag `WatchKey` may include command hex and the BLE local name. The local name is a hash of the VIN, not the VIN. The private key is not logged.
- The unit tests embed Tesla's published test private keys from `protocol.md`. Those keys are public. Do not enroll them on a vehicle.
- VCSEC allows about three concurrent BLE links. This app disconnects when the command finishes so it does not crowd out a phone key.

## Build

JDK 17 or newer, and Android SDK 35 (build-tools 34 or 35 are both fine).

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or wherever the SDK lives
./gradlew test assembleDebug
```

`test` runs the protocol module. `assembleDebug` produces:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` is gitignored. Point `ANDROID_HOME` at the SDK instead of committing a machine path.

## Install on a Pixel Watch

Pixel Watch has no USB debugging port. Use Wi-Fi debugging. The watch and the computer need to be on the same network.

1. On the watch: Settings > System > About. Tap the build number until developer mode is on.
2. Settings > Developer options. Turn on ADB debugging and Debug over Wi-Fi.
3. Wear OS 4 often shows an IP and port and a prompt on the watch:

   ```bash
   adb connect WATCH_IP:PORT
   ```

   Accept the prompt on the watch.

4. Wear OS 5 wireless debugging matches phone wireless debugging. Pair first, then connect. The pairing port and the debug port are different.

   ```bash
   adb pair WATCH_IP:PAIRING_PORT
   adb connect WATCH_IP:DEBUG_PORT
   ```

5. Install and launch:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.myen88.wearkey/.MainActivity
   ```

6. Add the tile from the watch face editor (tiles > Watch Key). Add the complication on a face that supports short text; its label is Unlock.

7. Remove it with `adb uninstall com.myen88.wearkey`, and delete the key on the car if it was enrolled.

If the scan finds nothing, stand closer, confirm the VIN, and confirm the car's phone-key Bluetooth is enabled. Some watches mishandle BLE scan filters, so this app matches the local name in software instead of relying on a hardware filter.

## Tests

```bash
./gradlew :protocol:test
```

Covered without a car: advertisement name `S1a87a5a75f3df858C` for VIN `5YJS0000000000000`, the spec's ECDH key `1b2fce19967b79db696f909cff89ea9a`, session-info HMAC, HVAC AES-GCM ciphertext, routable-message fields, unlock's zero enum still on the wire, whitelist and lock-state parsing, frame reassembly, the response counter window, and VIN check digits.

Not covered: GATT on a watch, Android Keystore, card-tap timing, or whether a given firmware build accepts the Android-device form factor. Compare a failure with `tesla-control -ble -debug` from vehicle-command if you need a byte-level diff. Do not log or enroll the test keys.

## License

Application code is MIT. See `LICENSE`.

`protocol/src/main/proto` is an unmodified copy of Tesla's protocol buffers, Apache-2.0, at the commit above. See `third_party/vehicle-command/LICENSE` and `NOTICE`.

## Limitations

- No walk-up unlock and no walk-away lock. See above.
- One car. The VIN is fixed after pairing until you remove the key on the watch and pair again.
- Trunk and frunk are open commands. Closing them is not in this build.
- Climate and battery depend on the infotainment computer being awake. Failure there does not block lock and unlock.
- The car can still have executed a command if the response is lost. Lock and unlock are safe to retry. The app retries once on a session-counter or epoch mismatch, and once if VCSEC says it is busy.
- English UI only.
