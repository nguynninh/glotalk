# Glotalk

Glotalk is an Android app that translates text directly on your screen. Use a
floating bubble to read another app in your language, or type and paste text to
translate inside Glotalk.

Translation runs on your device by default. You can optionally enable Gemini
with your own API key to help when on-device text recognition reads too little.

## Features

- **Screen translation:** translate visible text without switching apps or
  manually taking screenshots.
- **Two display modes:** replace the original text with a translation, or keep
  both visible for comparison.
- **Text translation:** type or paste text, copy the result, and listen to it
  with your device's text-to-speech service.
- **Audio translation:** use the existing bubble to recognize another app's
  speech locally with Whisper, translate its transcript with ML Kit, and read
  the translation aloud.
- **History and flashcards:** revisit text translations, bookmark entries, and
  review them as flashcards.
- **Realtime mode:** refresh screen translations as you scroll or change windows.
- **Floating controls:** drag the bubble to move it, tap for translation controls, or
  long-press for pause, language, settings and exit controls.
- **Languages:** translate between Vietnamese, English, Japanese, Korean and
  Chinese. The interface supports the same five languages and light/dark themes.

## Getting started

The `production` flavor is **Glotalk** (`vn.onplay.glotalk`). The `development`
flavor is **Glotalk Dev** (`vn.onplay.glotalk.dev`). Each supports `debug` and
`release` build types, giving four variants: `developmentDebug`,
`developmentRelease`, `productionDebug` and `productionRelease`. The two flavors
can be installed together and use separate app data and accessibility grants.

Glotalk requires Android 11 or newer. To build and install the app, follow the
[setup instructions](CONTRIBUTING.md#getting-started).

### Translate your screen

1. Open Glotalk and enable its accessibility service in Android Settings when
   prompted. This permission is needed to capture the screen and show translations.
2. Tap **Bật dịch nền** to enable the floating bubble.
3. Open the app you want to read, tap the bubble, choose the source and target
   languages and display mode, then tap **Bắt đầu dịch**.
4. Tap the red **✕** to dismiss a translation. Long-press the bubble to open its
   quick menu or stop background translation.

If screen translation stops working after reinstalling Glotalk, turn its
accessibility service off and on again in Android Settings.

### Translate text and review it

Open the text translation tab, choose your languages, then type or paste text.
Successful translations are saved to history, where you can bookmark, delete or
review entries as flashcards.

### Listen to another app

For direct streaming translation, open **Cài đặt → Dịch âm thanh trực tiếp · Gemini Live**,
enter your personal Gemini Live API key, enable **Gửi âm thanh đến Gemini Live**, and save.
Then enable **Dịch âm thanh** from the bubble while the source app is playing.
This mode connects directly from Android to Google Gemini Live over TLS WebSocket, sends
100 ms frames of 16 kHz mono PCM16, and plays translated audio as it arrives.
It uses `gemini-3.5-live-translate-preview` and plays 24 kHz mono PCM16 output.
Costs and quotas depend on your Google AI Studio project. The voice is AI-generated.
It auto-detects the source language and uses the selected target language.
Speech already in the target language is skipped.
There is no local Whisper model or TTS initialization in this mode. Network and
model latency still apply; no end-to-end latency target has been verified yet.
The Live key is encrypted in Android Keystore-backed no-backup storage, separately
from the screen translation key. Existing OpenAI keys and upload preferences are
not reused; enter a Gemini key and explicitly enable Live audio translation.
Changing saved Gemini Live settings stops the current audio session; restart it from
the bubble to apply. Clear the key and save to remove it from the device.

With Gemini Live disabled, the original offline workflow remains available:

1. Enable the background bubble, open the app playing speech, choose your
   languages in the bubble, and turn on **Dịch âm thanh**. No API key is needed.
2. Grant Android's audio recording and playback capture permissions. Glotalk
   captures playback from the selected app, without recording the microphone
   or automatically following other apps.
3. The first session downloads multilingual Whisper Small Q5_1 and Silero speech
   detection models (about 191 MB). Downloads are verified with SHA-256 and stored
   outside Android backups. ML Kit also downloads the selected language models
   on first use. Once these are downloaded, recognition and translation run offline.
4. Android reads translations using an installed offline voice for the target
   language. Install the voice through Android text-to-speech settings if needed.
   Neither captured audio nor transcripts are sent to a translation server.
5. Turn off the option or choose **Dừng dịch âm thanh** in the notification or
   bubble menu to stop. Changing languages, hiding the bubble, disabling
   accessibility or stopping the background service also stops audio translation.

Native recognition uses [whisper.cpp](https://github.com/ggml-org/whisper.cpp).
The first native build needs network access, Android NDK and CMake; their
versions and the source archive checksum are pinned in the build configuration.

## Limitations

- Apps can block playback capture. Silence alone cannot distinguish a blocked
  app from paused playback; Glotalk reports that no audio is being received.
- Offline audio translation processes speech at pauses, with a 5-second maximum chunk.
  Processing and speech introduce latency. If it cannot keep up, it stops with
  an error instead of silently dropping queued speech. Performance and accuracy
  depend on the phone, language and audio; simultaneous dubbing is not guaranteed.
- Before the streaming optimization, the tested Joy 4 running Android 11 took about 26 seconds with Whisper Small Q5_1
  for an 11-second English sample and 26 seconds for a 4.5-second synthetic
  Vietnamese sample. It did not keep up with live playback on that device, and
  the Vietnamese result still contained a word error. These are sample checks,
  not an accuracy benchmark or a guarantee for other devices.
- On emulator-5554, greedy decoding with a clip-sized encoder context reduced
  the same English sample from 4.45 to 1.85 seconds and Vietnamese from 3.63 to
  1.28 seconds. The multilingual Small model is unchanged. Initial translation
  model loading happens before capture starts; auto-detected source languages
  other than English can still need a source-model download on first use.
- The floating panel has independent original/media and translated/accessibility
  volume sliders. Changing original volume affects device media playback;
  translated speech uses Android's separate accessibility audio stream.
- Apps that block screen capture cannot be translated from the screen.
- The first translation for a language pair needs internet access to download
  its on-device model. Once downloaded, that pair can work offline.
- Text recognition and translation quality depend on the language pair and how
  clearly the text appears on screen.
- Realtime translation has a short delay and is not frame-by-frame live translation.
- Camera, microphone, file and link import controls in the text translation
  screen are not implemented yet.
- Read-aloud availability depends on the text-to-speech languages installed on
  your device.

## Privacy

On-device screen translation processes screenshots locally. Screenshots are held
in memory while a request is processed and released afterwards.

Gemini fallback is optional and disabled by default. Enabling it allows screenshots
that need cloud processing to be sent to Google using your API key. Text
translation history and app settings are stored locally. Audio translation uses
Whisper, ML Kit and an offline TTS voice on the device when Gemini Live is disabled.
Gemini Live audio translation is a separate, opt-in mode: selected-app audio is sent
to Gemini Live and translated speech is streamed back. The app does not record the
microphone or persist the audio/transcripts. A personal API key is encrypted with
Android Keystore in no-backup storage and is never included in the APK or logs.
Direct personal-key access is intended for the owner of the device; distributing
a shared developer key inside an APK is not supported.

## Development and integration

- [Contributing](CONTRIBUTING.md): setup, builds, tests and contribution guidelines.
- [Architecture](ARCHITECTURE.md): code organization and internal design.
- [Android app integration](CONTRIBUTING.md#external-app-integration): requesting
  screen translation from another app through AIDL.
- [Changelog](CHANGELOG.md): version history.

## License

No license file is currently included in this repository.
