# AnyDown for Android

A fully self-contained Android app. yt-dlp, a Python runtime and ffmpeg are
bundled into the APK and run on the phone. No server, no computer, no Tailscale,
no network hop — paste a link, pick a quality, the file lands in
`Downloads/AnyDown/`.

This is a sibling to the web version in `../backend` and `../frontend`, not a
port of it. See [What carries over](#what-carries-over).

---

## Getting the APK

The GitHub Actions workflow builds it, so you never need Android Studio.

1. Push this repo to GitHub.
2. Open **Actions → Android APK**, and either wait for the push-triggered run or
   hit **Run workflow**.
3. When it's green, download the **anydown-debug-apk** artifact from the run
   summary. It contains:
   - `app-arm64-v8a-debug.apk` — the one you want for any phone from roughly
     2017 onward
   - `app-armeabi-v7a-debug.apk` — older 32-bit devices
   - `app-universal-debug.apk` — both, if you're not sure
4. Copy it to the phone and open it. Android will ask you to allow installs from
   that source.

Pushing a `v*` tag also attaches the APKs to a GitHub Release, which is a
friendlier download link on a phone.

<details>
<summary>Building locally instead</summary>

Needs JDK 17 and the Android SDK. No Gradle wrapper is committed (a wrapper JAR
is a binary blob), so either open `android/` in Android Studio and let it
generate one, or:

```bash
cd android
gradle wrapper --gradle-version 8.9   # once
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

</details>

---

## Using it

- **Paste a link**, tap Fetch, tap a quality.
- Or **Share → AnyDown** from YouTube/TikTok/etc. The app pulls the URL out of
  the shared text and starts resolving immediately.
- Files go to `Downloads/AnyDown/`, and are handed to the media scanner so they
  show up in your gallery and the Files app.
- **Update** in the top right updates the bundled yt-dlp in place. This is how
  you fix a platform that stopped working — no new APK needed. It's the one real
  advantage this build has over the hosted version, where fixing extractor rot
  means a redeploy.

Downloads run in a foreground service with a progress notification. That isn't
decoration: Android freezes background processes, and without it any download
longer than a few seconds dies as soon as you leave the app.

---

## What carries over

Almost none of the backend, and that's the correct outcome. The streaming
pipeline, ffmpeg pipe muxing, fragmented MP4, CORS, rate limiting, the
concurrency cap, the merge-size guard and the SSRF allow-list all existed to
solve problems that only appear when bytes cross a network. On-device, yt-dlp
downloads and merges by itself.

What did carry over is the judgement, ported to Kotlin and unit tested:

| File | Ported from | What it decides |
|---|---|---|
| `domain/FormatPlanner.kt` | `backend/app/extractor.py` | Which qualities to offer, one per resolution, progressive over merge, avc1/MP4 over VP9/WebM, and the MP4-vs-MKV container call |
| `domain/Platforms.kt` | `backend/app/platforms.py` | Naming the site behind a link, with look-alike hosts never mistaken for the real one |
| `domain/Errors.kt` | `backend/app/errors.py` | Turning yt-dlp stderr into something readable |
| `domain/Filenames.kt` | `backend/app/streaming.py` | Filename sanitising, size and duration formatting |

Those four are deliberately free of Android and library imports, so CI unit
tests them on the JVM. Everything platform-specific sits behind them.

Two changes worth knowing:

**No resolution cap.** The server capped at 1080p to protect a 512 MB free-tier
instance. Your phone has storage and no request timeout, so every resolution the
platform offers is listed — including 1440p and 2160p. Those are usually
VP9/AV1, which MP4 can't hold reliably, so the label will say **MKV**. VLC plays
them; Android's default gallery sometimes won't.

**Any site yt-dlp supports.** The web version's allow-list doubled as an SSRF
control — a server must not be talked into fetching arbitrary addresses. There's
no server here, so the gate was blocking good links for no benefit (Reddit,
Vimeo, VK and LinkedIn never reached yt-dlp at all). Anything that parses as an
http(s) URL is now passed straight to yt-dlp, which supports well over a
thousand sites. `Platforms.ALL` only drives the "Detected: …" label; an unlisted
host still works, it's just unlabelled.

---

## Known risks

**ffmpeg is the weak link.** Without it yt-dlp can't merge separate video and
audio streams, which caps most platforms near 720p and breaks sites that offer
*only* separate tracks — Dailymotion and Threads among them. `ffmpeg-kit` was
retired and pulled from Maven in April 2025; this project uses the
`io.github.junkfood02.youtubedl-android:ffmpeg` artifact instead, which ships its
own binaries.

The app no longer hides this. When ffmpeg is missing the top bar reads
**LIMITED**, a warning notice explains why, and a link whose every option needs
merging reports exactly that rather than claiming no media was found. That last
case used to look like a broken extractor and was in fact a missing ffmpeg.

**The library API was written from its documentation, not a local compile.**
`data/YtDlpSource.kt` is the only file that touches youtubedl-android, and its
header lists the three symbols most likely to need adjusting (the update-channel
constant, the `execute` callback signature, and `VideoFormat` field names). If
the first CI run fails to compile, it will be there.

**Sideloading is getting stricter.** Google's developer verification opened to
all developers in March 2026 and starts enforcing on 30 September 2026 in Brazil,
Indonesia, Singapore and Thailand, expanding globally including the US in 2027.
It isn't a ban: apps from unverified developers will need an advanced install
flow with a 24-hour wait, or ADB from a computer, and there's a separate track
for hobbyists. Worth registering before it reaches your region. Google Play is
out regardless — its policy on downloading YouTube content matches Apple's.

**APK size.** A Python runtime plus yt-dlp plus ffmpeg per ABI is not small.
Install the arm64-v8a APK rather than the universal one, and drop `armeabi-v7a`
from `abiFilters` in `app/build.gradle.kts` if you want it smaller still.

---

## Legal

Unchanged from the web version: personal use only, on content you have the right
to download, public content only, no DRM. Most platforms' terms prohibit this
regardless of intent. Don't redistribute what you download, and don't publish
builds of this.
