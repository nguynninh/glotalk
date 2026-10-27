# VDubbing

VDubbing is an Android app that translates text directly on your screen. Use a
floating bubble to read another app in your language, or type and paste text to
translate inside VDubbing.

Translation runs on your device by default. You can optionally enable Gemini
with your own API key to help when on-device text recognition reads too little.

## Features

- **Screen translation:** translate visible text without switching apps or
  manually taking screenshots.
- **Two display modes:** replace the original text with a translation, or keep
  both visible for comparison.
- **Text translation:** type or paste text, copy the result, and listen to it
  with your device's text-to-speech service.
- **History and flashcards:** revisit text translations, bookmark entries, and
  review them as flashcards.
- **Realtime mode:** refresh screen translations as you scroll or change windows.
- **Floating controls:** drag the bubble to move it, tap for translation controls, or
  long-press for pause, language, settings and exit controls.
- **Languages:** translate between Vietnamese, English, Japanese, Korean and
  Chinese. The interface supports the same five languages and light/dark themes.

## Getting started

VDubbing requires Android 11 or newer. To build and install the app, follow the
[setup instructions](CONTRIBUTING.md#getting-started).

### Translate your screen

1. Open VDubbing and enable its accessibility service in Android Settings when
   prompted. This permission is needed to capture the screen and show translations.
2. Tap **Bật dịch nền** to enable the floating bubble.
3. Open the app you want to read, tap the bubble, choose the source and target
   languages and display mode, then tap **Bắt đầu dịch**.
4. Tap the red **✕** to dismiss a translation. Long-press the bubble to open its
   quick menu or stop background translation.

If screen translation stops working after reinstalling VDubbing, turn its
accessibility service off and on again in Android Settings.

### Translate text and review it

Open the text translation tab, choose your languages, then type or paste text.
Successful translations are saved to history, where you can bookmark, delete or
review entries as flashcards.

## Limitations

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
translation history and app settings are stored locally.

## Development and integration

- [Contributing](CONTRIBUTING.md): setup, builds, tests and contribution guidelines.
- [Architecture](ARCHITECTURE.md): code organization and internal design.
- [Android app integration](CONTRIBUTING.md#external-app-integration): requesting
  screen translation from another app through AIDL.
- [Changelog](CHANGELOG.md): version history.

## License

No license file is currently included in this repository.
