# All-in-One Video Downloader

A personal, self-hosted media downloader. Paste a public link from a supported
platform, pick a quality, and the file streams straight to your device.

Implements the PRD in `PRD.md`. Public content only, no accounts, no database,
and nothing is ever written to the server's disk.

```
frontend/   React + Vite + Tailwind SPA  ->  Netlify
backend/    FastAPI + yt-dlp + ffmpeg     ->  Render (Docker)
render.yaml Render blueprint
android/    self-contained Android app    ->  APK, see android/README.md
```

**Looking for the Android app?** `android/` is a standalone Kotlin app that runs
yt-dlp and ffmpeg on the phone itself — no server involved. It shares this
project's format-selection rules but none of its backend. Start at
[android/README.md](android/README.md).

---

## How it works

`POST /api/resolve` runs `yt_dlp.YoutubeDL().extract_info(url, download=False)`
and returns a de-duplicated list of qualities. `GET /api/download` re-derives
that same list, matches the requested format id against it, and streams the
result through one of two paths:

| Path | When | Server cost |
|---|---|---|
| **Direct** | one stream already carries video+audio over http(s) | proxy only, upstream `Content-Length` forwarded |
| **ffmpeg** | video and audio are separate (most 1080p), or the source is HLS/DASH | `-c copy` into fragmented MP4 on `pipe:1` |

Both are true streams. Memory use is bounded by the 256 KB chunk size, not by
video length, and the first bytes reach your phone within a second or two.

---

## Running it locally

**Backend** (Python 3.10+, ffmpeg on `PATH`):

```bash
cd backend
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
cp .env.example .env
uvicorn app.main:app --reload --port 8000
```

**Frontend** (Node 20+):

```bash
cd frontend
npm install
cp .env.example .env.local     # VITE_API_BASE=http://127.0.0.1:8000
npm run dev                    # http://localhost:5173
```

**Tests:**

```bash
cd backend && python -m pytest      # 74 tests
cd frontend && npm test             # allow-list parity with the backend
```

The Python suite is fully offline — yt-dlp is stubbed. The ffmpeg integration
tests generate their own media and serve it from a local HTTP server; they skip
automatically if ffmpeg isn't installed.

---

## Deploying

### Backend on Render

1. Push this repo to GitHub.
2. In Render: **New → Blueprint**, point it at the repo. `render.yaml` defines
   a free-tier Docker web service.
3. Set `APP_CORS_ORIGINS` to your Netlify URL once the frontend is live
   (e.g. `https://your-app.netlify.app`). Never `*`.

Docker is used rather than Render's native Python runtime because that runtime
has no ffmpeg and no way to add it — merged 1080p downloads would silently fall
back to 360p/720p forever.

<details>
<summary>Native Python runtime instead (no Docker)</summary>

Replace the service block in `render.yaml` with:

```yaml
    runtime: python
    rootDir: backend
    buildCommand: |
      pip install -r requirements.txt
      curl -sL https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz \
        | tar -xJ --strip-components=1 --wildcards '*/ffmpeg'
    startCommand: uvicorn app.main:app --host 0.0.0.0 --port $PORT --workers 1 --timeout-keep-alive 75
    envVars:
      - key: APP_FFMPEG_PATH
        value: ./ffmpeg
```

This depends on a third-party download staying available at build time, which
is why it isn't the default.
</details>

### Frontend on Netlify

1. **Add new site → Import from Git**, same repo. `netlify.toml` sets base
   directory, build command and publish path.
2. Set `VITE_API_BASE` to your Render URL in **Site settings → Environment
   variables**.
3. Redeploy.

`robots.txt` and an `X-Robots-Tag` header keep the site out of search indexes.
Bookmark the URL and don't share it.

---

## Configuration

All backend settings are `APP_`-prefixed environment variables (see
`backend/.env.example`). The ones worth knowing:

| Variable | Default | Notes |
|---|---|---|
| `APP_CORS_ORIGINS` | localhost dev ports | Comma-separated exact origins |
| `APP_RATE_LIMITS` | `10/minute,100/day` | Per client IP |
| `APP_MAX_HEIGHT` | `1080` | Higher resolutions are never offered |
| `APP_MAX_MERGE_BYTES` | `209715200` | Oversized merges fall back to a single stream |
| `APP_MAX_CONCURRENT_DOWNLOADS` | `2` | Extra requests get `503 BUSY` |
| `APP_APP_KEY` | unset | Optional shared secret, see below |

### Optional shared secret

There's no auth by design. If the URL leaks and rate limiting isn't enough, set
`APP_APP_KEY` on Render and `VITE_APP_KEY` to the same value on Netlify. The
frontend then sends it as `X-App-Key`. It's a password baked into a static
site — a speed bump for bots, not real access control.

---

## Where this departs from the PRD

Three deliberate changes. Each one is required to deliver what the PRD asks for.

**1. ffmpeg takes URLs as inputs, not two pipes.**
Section 7.2 describes piping the video and audio streams into ffmpeg's stdin
concurrently. A process has one stdin, and ffmpeg can only read one `pipe:0`,
so two concurrent piped inputs aren't possible without named pipes and extra
moving parts. Instead ffmpeg is handed both stream URLs directly (with the
platform's required headers) and writes to `pipe:1`. Same outcome — data flows
through in one pass and never touches disk.

**2. `/api/download` is a GET, not a POST.**
Section 8 sketches a POST. But only a plain GET navigation hands a response to
the browser's own download manager. With a POST the SPA would have to `fetch`
the response and assemble a Blob, which buffers the entire video in browser
memory before saving — precisely the "download to the website, then download to
your phone" double-hop section 8 rules out. GET keeps the download a true
pass-through, and gives you a real progress bar in the notification shade.

**3. The size threshold guards bandwidth and timeouts, not RAM.**
Section 7.2 assumes the in-memory merge could exhaust Render's 512 MB. It
can't: the pipeline is a genuine stream, so resident memory stays around the
chunk size regardless of whether the video is 5 MB or 5 GB. The real free-tier
risks are the request timeout and the bandwidth quota. `APP_MAX_MERGE_BYTES` is
still implemented and still auto-falls back — it's just guarding the constraint
that actually binds. Open question 1 in section 15 resolves to: tune it against
download duration, not memory.

Smaller notes: merged output is *fragmented* MP4 (`frag_keyframe+empty_moov`),
since a normal MP4 writes its index at the end and can't be streamed; VP9/Opus
sources produce `.mkv` because MP4 can't hold them reliably; and HLS/DASH
sources route through ffmpeg even when a single stream carries both tracks,
because a playlist can't be fetched with a plain GET.

---

## Maintenance

Extractors break when platforms change — this is the expected failure mode, not
a bug in this app. When something stops resolving:

```bash
cd backend
pip install -U yt-dlp
```

then bump the pin in `requirements.txt` and redeploy. `GET /api/health` reports
the running yt-dlp and ffmpeg versions.

Instagram, Facebook, Snapchat and Threads are best-effort. Most of their
content needs a logged-in session, and this app deliberately doesn't support
cookies, so those links will often return `PRIVATE_CONTENT`. That's the
designed boundary, not a failure.

---

## Legal

Personal use only, on content you have the right to download. Most platforms'
terms prohibit this regardless of intent. Don't deploy it publicly, don't share
the URL, don't redistribute what you download. No DRM-protected content is
supported and none should be attempted.
