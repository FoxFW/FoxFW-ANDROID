FoxFW Companion
===============

An Android app that pairs with a Flipper Zero running FoxFW. It uses the phone's
screen, processor and network connection for the jobs that are slow or awkward on
the Flipper itself, and it can also drive a wired Fox ESP32 board for Wi-Fi and
Bluetooth work.

The app connects to the Flipper over Bluetooth LE or USB (OTG). Sub-GHz, NFC/LF
and settings requests are handled by a small helper app that ships with FoxFW
(`fox_android_companion.fap`); the companion launches it on the device when a
feature needs it.

Package id: `com.foxfw.companion`.


What it does
------------

- Captures Sub-GHz key fobs through the Flipper's CC1101 and decodes them on the
  phone. For rolling-code fobs that it can decrypt, it generates and transmits
  the next valid code rather than replaying the captured packet.
- Mirrors and remote-controls the Flipper screen, with an on-screen keyboard.
- Browses the SD card and moves files between the phone and the device.
- Installs firmware updates over the existing link.
- Reads, writes and emulates NFC and 125 kHz tags, and drives a Chameleon over
  Bluetooth.
- Drives a Fox ESP32 for Wi-Fi and Bluetooth work, using one shared connection
  and a single console across all of the ESP32 pages.
- Reads and writes the Flipper's own settings.


Requirements
------------

- Android 8.0 (API 26) or newer. Builds target `arm64-v8a` only.
- A Flipper Zero running FoxFW.
- A USB-OTG cable or adapter for a wired connection.

The UI is Jetpack Compose. The Sub-GHz decode engine is written in Python and
bundled with Chaquopy to run on the device; its sources are under
`app/src/main/python`.


Building
--------

You need JDK 17 and the Android SDK. Android Studio provides both; point it at
the project and build, or use Gradle from the command line:

    ./gradlew :app:assembleDebug       # debug APK
    ./gradlew :app:assembleRelease     # release APK

On Windows use `gradlew.bat`. APKs are written to `app/build/outputs/apk/`.

Debug builds need no setup. They are signed with the local debug key, marked
debuggable, and include a couple of developer-only panels, so they are for
development rather than distribution.

Release signing is read from a `keystore.properties` file at the project root,
which is not committed. Copy `keystore.properties.sample` to
`keystore.properties` and fill in the four values (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`), then generate the keystore it points at:

    keytool -genkeypair -v -keystore foxfw-release.jks -alias foxfw \
        -keyalg RSA -keysize 2048 -validity 10000

If `keystore.properties` is missing, a release build falls back to the debug key
so the project still builds for anyone. Keep the keystore and its passwords
backed up: if they are lost, later builds can no longer install over an existing
copy without uninstalling it first.

On Windows, `compile_apk.bat` offers a small menu for building either APK and
will create the keystore the first time if one does not already exist. It is
left out of git because it contains the signing password.


Project layout
--------------

    app/src/main/java/com/foxfw/companion   Kotlin / Compose app
    app/src/main/python                     Sub-GHz decode engine (Python)
    app/build.gradle.kts                    build config and signing
    hosttest/                               host-side tests for the decode engine


Notes
-----

The app does not reflash or alter the firmware on its own, apart from the update
feature, which installs an official FoxFW build. Its radio features depend on the
Flipper and, where used, the Fox ESP32. Only capture, transmit or test with
hardware and signals you own or are permitted to work with.
