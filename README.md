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

- Unlock, lock, and Drive (remote start, second tap). Prefer PIN to Drive on the car.
- Open trunk, or Close trunk when VCSEC reports the rear trunk open (ajar, opening, closing, or unlatched). The second tap sends `CLOSURE_MOVE_TYPE_OPEN` or `CLOSURE_MOVE_TYPE_CLOSE`. Until a status has been read, or the car reports the trunk state as unknown, both buttons are shown. The frunk stays open-only: on Model 3, Y, S, and X the frunk hood latches by hand and does not power-close.
- Climate on and climate off (infotainment).
- Refresh reads VCSEC closure status and, if infotainment answers, battery percent (`battery_level`, otherwise `usable_battery_level`).
- The Watch Key tile has Unlock and Lock. The Unlock complication does the same. Both open this app and send the command. That is one tap, and the watch has to be in BLE range. It is not walk-up unlock.

## Auto key

Auto key is off until you turn it on. It is the walk-up path, modeled on how phone-key apps such as Tessie describe their watch key: stay connected to the car, and let the car decide from that link's signal. There is a second path in this app for when the car does not do that by itself.

### What is confirmed

- The BLE name, GATT service, and write/notify characteristics are in Tesla's vehicle-command spec.
- VCSEC accepts an authenticated session on that link and will run RKE unlock, lock, and remote drive (`RKE_ACTION_REMOTE_DRIVE`) for an enrolled key. The Drive button sends remote drive. Turn on PIN to Drive in the car if the watch might be near the car while someone else is in the seat.
- A foreground service of type `connectedDevice`, plus a partial wake lock, is what keeps the watch's radio up after the screen sleeps. Wear OS will not keep a BLE client connected in the background without that. The notification is low priority and persistent.
- The first scan is low latency for about eight seconds, then low power. After a drop, the service reconnects with backoff up to 30 seconds. A button command pauses Auto key, uses the radio, then Auto key connects again.
- VCSEC only keeps about three BLE links. Do not run the official Tesla app's phone key on this same watch at the same time. The two apps will fight over the radio and over those slots.

### What is speculative

The official vehicle-command protos at the pinned commit do not define the phone-key presence challenge. Public captures (VCSEC app protos around 4.20, and the NDSS 2023 phone-key paper) show the car sending a `FromVCSEC` authentication request with a token, and the phone answering a `ToVCSEC` signed message of type `SIGNATURE_TYPE_AES_GCM_TOKEN`. This app answers messages that match that layout:

- The AES key is the vehicle-command session key, `SHA-1(ECDH X)[:16]`. Older write-ups say "the ECDH shared secret" and do not spell out the SHA-1 step.
- The nonce is 12 bytes, with the 4-byte counter in the last four bytes. The public notes only say the counter is four bytes. Java's AES-GCM wants a 12-byte nonce.
- `keyId` is the first four bytes of SHA-1 of the watch public key, which those write-ups do agree on.
- The level in the reply is the level the car asked for when the smoothed RSSI is at or above the near line, and `NONE` otherwise. The distance field is a hint derived from RSSI, not a measured range.

If the car ignores that reply, it will not treat the watch as a present phone key no matter how long the GATT link stays up. That is the likely outcome on firmware that only speaks the newer session protocol. Treat "the car shows this key as connected" as something you have to check on your own car.

### Signal fallback

When "Signal unlock" is on (the default once Auto key is on), the watch also sends an authenticated RKE unlock or lock from RSSI:

- Samples are averaged.
- Crossing the near threshold sends one unlock. It will not unlock again until it has locked.
- Staying at or below the far threshold for the hold time sends one lock. A short dip does not.
- Commands are at least four seconds apart. A command that was rate-limited is sent later only if the watch is still on that side of the threshold.
- Near, far, and hold are on the Auto key screen, next to a live RSSI line, so you can calibrate beside the car. Near must be a stronger signal than far. Tapping Near steps it; tapping Far steps the other way.

This fallback does not make the car display the key as connected. It just sends the same lock and unlock the buttons send.

### Setup

1. Pair the watch first.
2. Turn Auto key on. The watch asks for Bluetooth, notifications, fine location, and background location, then asks to ignore battery optimizations. The connection itself is held by the foreground service. Location must be allowed and switched on: Android hides iBeacon advertisements, which is how the car announces itself, when an app scans with `neverForLocation` or when Location is off.
3. Leave Bluetooth on. After a reboot the service starts again if Auto key is still on.
4. The Watch Key tile has an Auto on/off line. It opens the app and flips the setting.

Expect a real battery cost. A wake lock plus a BLE link is the opposite of the on-demand commands above. Turn Auto key off when you do not need walk-up.

### Security

A BLE presence key can be relayed: a pair of radios can make the car think the watch is next to it. This app does not add a distance bound the car can trust. PIN to Drive is the control that stops a relay from letting someone start the car. Unlock from a relay is still possible, which is the same class of limit as the official phone key.

The Owner key on the watch can still authorize commands while Auto key is connected. A lost watch should have its key deleted on the car screen, and Auto key turned off.

## Security

- The private key is generated on the watch and is not uploaded anywhere. Commands are signed with ECDH plus AES-GCM. There is no OAuth token in this app.
- Enrollment needs a physical key card and a confirmation on the car screen. A nearby attacker who only has this APK cannot add themselves.
- The key's role is Owner, so it can authorize the same class of commands as your phone key, including key management. If the watch is lost or stolen, delete this key on the car screen. Uninstalling the app is not enough. A watch PIN is the practical protection: the keystore key is not gated on a biometric, because Pixel Watch 2 has no fingerprint sensor and a prompt would break the tile.
- The VIN and the session cache (epoch, counter, vehicle public key, clock offset) stay in app-private storage. The shared AES key is derived again with the keystore when needed and is not written to disk. `android:allowBackup` is false.
- Debug builds are signed with the local Android debug keystore, which is not in this repo. Logcat tag `WatchKey` may include command hex and the BLE local name. The local name is a hash of the VIN, not the VIN. The private key is not logged.
- The unit tests embed Tesla's published test private keys from `protocol.md`. Those keys are public. Do not enroll them on a vehicle.
- VCSEC allows about three concurrent BLE links. This app disconnects when the command finishes so it does not crowd out a phone key.

## Set up your computer

You need three things: a JDK (Java 17 or newer), the Android SDK (platform 35 plus build-tools), and `adb` (platform-tools) to install the APK on the watch. Pick your OS. No Android Studio is required, but if you already have it, its SDK works and you can skip to step 3.

### macOS (Homebrew)

```bash
# 1. Java 17
brew install openjdk@17
sudo ln -sfn "$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-17.jdk

# 2. Android command-line tools, then the SDK pieces
brew install --cask android-commandlinetools
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 3. Make it permanent (zsh)
cat >> ~/.zshrc <<'RC'
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
RC
source ~/.zshrc
```

### Windows (PowerShell)

```powershell
# 1. Java 17
winget install Microsoft.OpenJDK.17

# 2. Android command-line tools: download "Command line tools only" from
#    https://developer.android.com/studio#command-line-tools-only
#    and unzip so the path is  %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 3. Make it permanent, then open a new terminal
setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"
setx PATH "$env:PATH;$env:LOCALAPPDATA\Android\Sdk\platform-tools"
```

On Windows, use `gradlew.bat` instead of `./gradlew` below.

### Linux (Debian / Ubuntu)

```bash
# 1. Java 17
sudo apt update && sudo apt install -y openjdk-17-jdk unzip

# 2. Android command-line tools
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools" && cd /tmp
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*_latest.zip && mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 3. Make it permanent
echo 'export ANDROID_HOME="$HOME/Android/Sdk"' >> ~/.bashrc
echo 'export PATH="$ANDROID_HOME/platform-tools:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

### Check it worked

```bash
java -version     # should say 17 or newer
adb version       # should print a version, not "command not found"
echo $ANDROID_HOME
```

Then clone and build:

```bash
git clone https://github.com/myen88/wearos-tesla-key.git
cd wearos-tesla-key
./gradlew test assembleDebug
```

The first build downloads Gradle 8.11.1 and dependencies, so it takes a few minutes.

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

6. Set the VIN from the computer instead of typing it on the watch. The app checks the check digit, saves the normalized VIN, and shows "VIN saved" or the validation error.

   ```bash
   adb shell am start -n com.myen88.wearkey/.MainActivity --es vin YOUR_VIN
   ```

7. Add the tile from the watch face editor (tiles > Watch Key). Add the complication on a face that supports short text; its label is Unlock.

8. Remove it with `adb uninstall com.myen88.wearkey`, and delete the key on the car if it was enrolled.

The car advertises an Apple iBeacon next to the `S…C` local name. Scanning with `neverForLocation` drops those advertisements on Android, which is why this app asks for location and requires the watch Location toggle to be on. Pairing and every command use a low-latency scan of about 18 seconds with no hardware name filter. If nothing matches, the message says whether the scan saw no devices at all, or saw other Bluetooth devices and Tesla names. Nearby cars on the watch lists every `S` + 16 hex characters + `C` name and its RSSI, plus the name expected for the saved VIN. When exactly one of those names is that VIN, the screen says "Matches your VIN". An iBeacon by itself is not used as a VIN match: vehicle-command does not define that UUID from the VIN.

## Tests

```bash
./gradlew :protocol:test
```

Covered without a car: advertisement name `S1a87a5a75f3df858C` for VIN `5YJS0000000000000`, parsing complete and shortened local names, case-insensitive Tesla-name matching, the spec's ECDH key `1b2fce19967b79db696f909cff89ea9a`, session-info HMAC, HVAC AES-GCM ciphertext, routable-message fields, unlock's zero enum still on the wire, whitelist and lock-state parsing, frame reassembly, the response counter window, VIN check digits, the RSSI hysteresis state machine, and a round-trip of the speculative phone-key challenge encoding.

Not covered: GATT on a watch, Android Keystore, card-tap timing, or whether a given firmware build accepts the Android-device form factor. Compare a failure with `tesla-control -ble -debug` from vehicle-command if you need a byte-level diff. Do not log or enroll the test keys.

## License

Application code is MIT. See `LICENSE`.

`protocol/src/main/proto` is an unmodified copy of Tesla's protocol buffers, Apache-2.0, at the commit above. See `third_party/vehicle-command/LICENSE` and `NOTICE`.

## Limitations

- Auto key's phone-key challenge reply is speculative. The RSSI fallback is the part that does not depend on the car cooperating. See Auto key.
- One car. The VIN is fixed after pairing until you remove the key on the watch and pair again.
- The frunk is open-only. Power close is the rear trunk, and only when the car reports that lid open.
- Climate and battery depend on the infotainment computer being awake. Failure there does not block lock and unlock.
- The car can still have executed a command if the response is lost. Lock and unlock are safe to retry. The app retries once on a session-counter or epoch mismatch, and once if VCSEC says it is busy.
- English UI only.
