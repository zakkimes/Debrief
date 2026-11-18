# Debrief

An Android data logger and driving coach for autocross. Debrief records each run from a RaceBox GPS and the car's own data over OBD-II. After the session it shows where you gained or lost time against your other runs. The coaching is in driver's terms: where to brake, where to turn in and apex, and how much brake to carry into the turn.

It also syncs GoPro video to your runs by GPS time, so the video, the map and the charts all follow the same moment.

## What it needs

- **An Android tablet,** Android 10 or newer. It's built and tested on a Galaxy Tab A9.
- **A RaceBox Micro or Mini** for GPS and motion. This one is required.
- **OBDLink MX+, optional,** for brake pressure, throttle, RPM and gear. Without it, Debrief coaches from the RaceBox alone.
- **A GoPro, optional,** for video synced to each run.

## Install

Download the APK onto the tablet and open it. Android asks you to allow installs from that source the first time. Every version is signed with the same key, so a new version installs over the old one and keeps your sessions.

## Your data

Everything Debrief records stays on the tablet. Nothing is uploaded. Satellite map tiles are downloaded and cached while you're online.

- **To move to a new tablet,** or keep a copy safe, use **Review → Other days → Back up everything**. It makes one file you can copy off the tablet.
- **To put a backup back,** use **Restore a backup** on the same screen.

## Building

1. Install JDK 17 and the Android SDK (platform 35).
2. Point `local.properties` at the SDK: `sdk.dir=...`
3. Build:
   ```bash
   ./gradlew assembleDebug
   ```

On a machine without the release key in `~/.debrief/`, builds are signed with that machine's debug key. A debug-signed build won't install over a release-signed one.

## Tests

```bash
./gradlew testDebugUnitTest
```

Tests that replay full sessions read them from `pulled/`. That folder is never committed, so on other machines those tests are skipped.

## Fonts

Anton and Barlow Condensed, both under the [SIL Open Font License 1.1](https://openfontlicense.org).
