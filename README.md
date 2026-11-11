# Offline Hinglish ASR

A small Android app for stress-testing Google's **ML Kit GenAI Speech Recognition API (alpha)** on
real, non-Pixel phones, using everyday **Hinglish** (code-mixed Hindi + English) sentences. The UI is
built for screen recording: large mode, locale, and score labels, plus an **OFFLINE ✈️ / ONLINE** badge
that shows airplane mode was on.

## Screenshots

**Real run on an emulator** (API 36.1 with Google Play). ML Kit reports `UNAVAILABLE` in both modes, and airplane mode is on:

<img src="docs/screenshots/emulator-unavailable.png" width="260" alt="Speech recognition UNAVAILABLE screen on the emulator, with Mode NONE and the OFFLINE badge">

**UI walkthrough.** These come from the instrumented UI test (`TestModeFlowTest`), which uses a *scripted*
recognizer because ML Kit is unavailable on the emulator. The transcripts and scores are test data,
**not real ASR results**.

| Home + read-back | Test: listening (partial text) | Test: judge |
|---|---|---|
| <img src="docs/screenshots/home.png" width="240" alt="Home screen with transcript and read-back engine label"> | <img src="docs/screenshots/test-listening.png" width="240" alt="Test screen while listening, partial text in grey"> | <img src="docs/screenshots/test-judge.png" width="240" alt="Test screen with transcript and Meaning survived / Miss buttons"> |

| Results: both locales | Model download |
|---|---|
| <img src="docs/screenshots/results.png" width="240" alt="Results with hi-IN and en-IN scores side by side"> | <img src="docs/screenshots/downloading.png" width="240" alt="Feature status DOWNLOADING with progress bar"> |

## What it tests

- **Feature status** for the chosen locale (`AVAILABLE` / `DOWNLOADABLE` / `DOWNLOADING` / `UNAVAILABLE`),
  with a **Download model** button and a progress bar.
- **Mode:** it asks for `MODE_ADVANCED` (GenAI / Gemini Nano) first and falls back to `MODE_BASIC`
  when ADVANCED reports `UNAVAILABLE`.
- **Locale:** `hi-IN` vs `en-IN`, set through `speechRecognizerOptions { locale = … }`.
  hi-IN is documented (beta) for both modes. en-IN is **not** in the documented list for either mode,
  so the app just reports whatever status the device returns.
- **Fully offline recognition** from the mic. Partial text shows in grey and final text in white.
- **Read-back (optional):** ML Kit GenAI Text-to-Speech, with a fallback to the platform `TextToSpeech`.
  The UI labels which engine spoke.

## Stack

Kotlin · Jetpack Compose · Material 3 · single activity · one `AndroidViewModel` · version catalog ·
minSdk 31 · AGP 9.5.1 · Kotlin 2.4.21 · Gradle 9.9.1 · Compose BOM 2026.09.00

| Artifact | Version |
|---|---|
| `com.google.mlkit:genai-speech-recognition` | `1.0.0-alpha2` |
| `com.google.mlkit:genai-text-to-speech` | `1.0.0-alpha1` |
| `com.google.mlkit:genai-common` (transitive) | `1.0.0-beta5` |

## Run it

```bash
./gradlew :app:installDebug
adb shell am start -n com.akshay.offlineasr/.MainActivity
```

1. Launch with internet on and grant the mic permission. If the status says `DOWNLOADABLE`, tap **Download model**.
2. Turn on **airplane mode**. The badge switches to **OFFLINE ✈️**.
3. Tap **Start 10-sentence test**. For each sentence, tap the mic, read the sentence, tap stop, then judge it:
   **✓ Meaning survived** or **✗ Miss**. Spelling variants and Devanagari vs Roman script both count as correct.
4. On the results screen, tap **Run en-IN** to repeat the test in the other locale. Both scores appear side by side.
5. Tap **Copy results as Markdown** and paste the output into the table below.

## The 10 sentences

1. Kal office nahi aa raha, mera laptop crash ho gaya.
2. Bhai, meeting ko 4 baje shift kar do please.
3. Mummy ne bola hai ki aaj Bhai Dooj pe ghar jaldi aana.
4. Ye bug sirf release build mein aa raha hai, debug mein nahi.
5. Mujhe Udaipur se Jaipur ka train ticket book karna hai.
6. Ek kaam karo, PR merge karne se pehle tests chala lo.
7. Chhath ke liye Bihar ki saari trains full hain yaar.
8. Mera UPI payment fail ho gaya but paisa kat gaya.
9. Compose mein recomposition bahut zyada ho raha hai.
10. Weekend pe Android Engineers ka meetup hai, aa jaana.

## Results

| Device | Mode | Locale | Score | Missed words |
|---|---|---|---|---|
| Android Emulator (sdk_gphone64_arm64, Android 16 / API 36.1, Google Play) | NONE — ADVANCED & BASIC both `UNAVAILABLE` | hi-IN | n/a (could not run) | n/a |
| Android Emulator (sdk_gphone64_arm64, Android 16 / API 36.1, Google Play) | NONE — ADVANCED & BASIC both `UNAVAILABLE` | en-IN | n/a (could not run) | n/a |
|  |  | hi-IN |  |  |
|  |  | en-IN |  |  |

The blank rows are for a real phone. Use **Copy results as Markdown** in the app to get the per-sentence transcripts.

## Tests

Run these on an emulator or a phone:

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w com.akshay.offlineasr.test/androidx.test.runner.AndroidJUnitRunner
```

- `TestModeFlowTest` drives the real UI and the real test-mode state logic with a **scripted** recognizer.
  It covers 10 sentences in hi-IN (7/10), a full en-IN run (10/10), both scores side by side, the copied
  Markdown, and the UNAVAILABLE and DOWNLOADING screens. Screenshots are saved to
  `/sdcard/Android/data/com.akshay.offlineasr/files/shots/`. The scripted recognizer exists only in tests
  and never ships in the app.
- `TtsEngineTest` runs the **real** TTS path and reports which engine spoke.

Emulator result (Pixel-class AVD, API 36.1 with Google Play, arm64): ADVANCED and BASIC are both
`UNAVAILABLE` for hi-IN and en-IN. The image has no AICore, and its Speech Services app is from Aug 2025.
ML Kit TTS returns no voices there, so the app falls back to platform TTS. You need a real device for real results.

## Caveats (alpha API)

- The API only lets you *request* a mode (`preferredMode`). It has no getter for the mode actually in
  use, so the "Mode" label shows the requested mode on the first client whose `checkStatus()` was not `UNAVAILABLE`.
- Google documents ADVANCED for Pixel 10/11 and Gemini Nano chipsets only. It is not supported on devices
  with an unlocked bootloader.
- ML Kit GenAI TTS officially supports a short list of devices (Pixel 8+, Galaxy S25/S26 and a few others)
  and has no en-IN voice. On other devices the app falls back to platform TTS and says so.
- **Bug in `genai-text-to-speech:1.0.0-alpha1`:** the documented call `speechSynthesizerOptions(Locale.US)`
  compiles but throws `NoSuchMethodError: speechSynthesizerOptions$default` at runtime, because the AAR
  has no Kotlin default-argument bridge. Workaround: pass the lambda explicitly, as in
  `speechSynthesizerOptions(locale) {}`.
- **Docs mismatch in `genai-speech-recognition:1.0.0-alpha2`:** the docs show `download` as a property,
  but the API is a function, `download()`.

Docs: [Speech recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android) ·
[Text to speech](https://developers.google.com/ml-kit/genai/text-to-speech/android)
