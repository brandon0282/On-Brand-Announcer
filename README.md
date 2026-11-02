# On-Brand Announcer

Hear your notifications read aloud through your headphones, earbuds or car. Free, no ads, no account, no tracking.

**Private by design:** the app has **no internet permission**, so nothing it sees can leave your phone. It doesn't ask for contacts, location or phone permissions either.

## Install (Android 8.0+)

1. On your phone, open the [latest release](../../releases/latest) and download the `.apk` file.
2. Open the downloaded file. If Android asks, allow your browser or Files app to **install unknown apps**, then tap **Install**.
   - If Play Protect warns that it doesn't recognize the app, tap **More details → Install anyway**. That warning appears for any app not from the Play Store.
3. Open **Announcer**, tap **Grant Access**, and switch Announcer on in the screen that opens.
4. Connect headphones and you're set. By default it only talks when headphones or a car stereo are connected; you can change that in **Settings → Output**.

To update, download the newer `.apk` from Releases and install it over the old one. Your settings are kept.

## What it does

- Reads notifications aloud with the app name, sender and message ("WhatsApp. Mom: on my way")
- **Summary dashboard**: what was read or skipped today and this week, top apps, and why things were skipped
- **86+ voices** with Female/Male filters, previews and speaking styles (Natural, Brisk, Calm, Deep, Bright, Robot), plus a different voice per app or per device
- **Per-app control**: mute apps, rename them, set a word limit
- **Quiet hours**, **priority people** who always get through, and **per-device rules** (e.g. on in the car, off with work earbuds)
- **Combines bursts**: "3 new messages from Mom. Latest: …"
- **Caller ID**, **time announcements**, a **home-screen widget** and a **Quick Settings tile**
- Block/allow words, privacy mode, Do Not Disturb support, and an activity log that explains every skipped notification
- A full **User Guide** inside the app: Settings → User Guide

## Build it yourself

Requires JDK 17–21 and the Android SDK.

```sh
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without the maintainer's signing key, release builds are signed with your local debug key. That APK can't install over an official release, so uninstall the official one first.
