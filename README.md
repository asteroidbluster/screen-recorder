# Screen Recorder

An open-source Android screen recorder with the audio options built-in
recorders usually refuse you: your voice, the game's sound, or both at
once.

## Features (v1.1)

- **Three audio modes**: microphone only, internal audio only, or
  microphone + internal audio mixed together
- **Video quality**: 720p, 1080p, or original size (capped at 1920 px on
  the long side)
- **Frame rate**: 30 or 60 fps
- **Material 3 UI** — your last choices are remembered
- Records while you switch apps, via a foreground service with a
  notification Stop button
- Saves to `Movies/ScreenRecorder` on your phone
- No accounts, no analytics, no network: recordings never leave your device

## How it works under the hood

Microphone-only recording could use Android's simple MediaRecorder, but
internal audio cannot: it needs AudioPlaybackCapture (Android 10+) plus a
hand-built pipeline. So v1.1 records everything through MediaCodec +
MediaMuxer: the screen goes to an H.264 encoder surface, mic and/or
playback-capture PCM goes to an AAC encoder (mixed sample-by-sample
when both are on), and both tracks are muxed into one MP4.

## How to install (no PC needed)

1. Open this repo's **Actions** tab, pick the latest successful run of
   "Build debug APK", and download the `screen-recorder-debug-apk`
   artifact (a zip containing `app-debug.apk`).
2. Unzip it on your phone and tap the APK to install. Allow "install
   unknown apps" for your browser when asked.
3. Open **Screen Recorder**, leave the mic toggle on, tap **Start
   recording**, and approve the system screen-capture dialog (Android
   asks every time — no app can record silently).
4. Stop from the app or from the notification. The video lands in
   `Movies/ScreenRecorder`.

## Permissions, and why each is needed

- **Record audio** — your voice, only while recording.
- **Foreground service (media projection)** — keeps the recording alive
  when you leave the app.
- **Notifications** — the ongoing notification with the Stop button.

Screen capture itself is gated by Android's system dialog on every
recording. The app cannot start recording without you approving it.

## Build it yourself

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 34 and JDK 17. GitHub Actions builds the debug APK
on every push to `main` (see `.github/workflows/android.yml`).

## v1.1 limits (honest list)

- Internal audio needs Android 10+. Some apps opt out of being captured,
  and system UI sounds are not captured — that's Android's rule, not the
  app's.
- No camera overlay, no pause button, no rotation handling mid-recording.
- Minimum Android 8.0 (API 26); internal modes are hidden below Android 10.
- The app cannot start recording without you approving Android's
  screen-capture dialog every time.

## License

MIT. See [LICENSE](LICENSE).
