# Edge Mobile (Android)

Android client for Edge. Speaks the desktop protocol described in `PROTOCOL.md`.

* `core/` – pure Kotlin/JVM protocol (discovery, ECDH + AES-GCM framing, chat, file send/receive). No Android dependencies; tested against the real desktop `network.js`.
* `app/` – Android app: foreground service, Compose UI, saves received files to `Downloads/Edge/`.

## Build

1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. Run on a device, or `./gradlew :app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.
3. Put the phone and the computer on the same Wi-Fi, open Edge on both.

If the devices don't find each other (some routers block UDP broadcast), type the computer's LAN IP into the box on the main screen.

## Status

Works: discovery, handshake/encryption, chat, send and receive files, accept/decline prompt, progress, messenger-style UI with desktop themes, profile pictures (both directions), Android Share menu target, auto-reconnect, image/video thumbnails, in-app image zoom and video player, Edge streaks.
Not yet: folder transfer (folder requests are declined), WAN mode, reactions, pause/resume buttons.

## Run the protocol test (no Android needed)

`core/src/test/kotlin/InteropMain.kt` connects to a running desktop Edge on 127.0.0.1:42069 and exchanges a chat message and a file each way.

## Latest additions (uncompiled Android layer)
- Folder send/receive (receives into Downloads/Edge/<folder>), via the folder button next to the paperclip.
- Pause / resume (both directions) and cancel (sender only) buttons on transfers.
- Replies and emoji reactions: long-press a message.
- Background: foreground service + partial wake lock (setting "Stay connected with screen off"), notifications for messages and file/folder requests with Accept/Decline buttons, battery-optimisation shortcut in Settings.
- Needs `androidx.documentfile` (already added to app/build.gradle.kts).
Core logic is tested against the real desktop network code; the app/ module has not been compiled here.

## WAN mode
"Connect by address" has an "Over the internet (WAN)" switch. The address is remembered and re-dialled
automatically (also after network changes). Tap the name in a chat to see the security code and compare it
with the desktop's. The computer must be reachable: UPnP/port-forward, ideally with a dynamic-DNS name.
