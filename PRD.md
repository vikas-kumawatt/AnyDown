# Product Requirements Document (PRD)
## All-in-One Video Downloader (Personal Use)

**Version:** 1.0
**Status:** Draft
**Owner:** [Your Name]
**Last Updated:** 2026-08-26

---

## 1. Overview

A self-hosted web application that lets a single user (or small trusted group) paste a URL from a supported social platform and download the media (video/audio/image) to their local device. The app consists of a lightweight Single Page Application (SPA) frontend and a backend service responsible for resolving and streaming media.

**Intended use:** Personal, non-commercial use only, on content the user has the right to download (own content, or content whose creator has given permission, or where platform ToS/local law permits).

---

## 2. Goals

- Single UI to paste a link from any supported platform and get a downloadable file.
- Support the widest practical set of platforms via a proven extraction engine (yt-dlp) rather than custom per-platform scraping.
- Simple, fast, self-hostable (Docker) — no third-party SaaS dependency.
- Reasonably resilient to platform changes (isolate extraction logic behind an interface so it can be updated independently of the frontend).

## 3. Non-Goals

- No public, multi-tenant SaaS product. Not designed for anonymous internet-facing use at scale.
- No support for private, login-gated, or friends-only content — **public content only**. If a URL requires a logged-in session to view, it's out of scope.
- No circumvention of DRM-protected content (e.g., paid/subscription video).
- No guarantee of permanent support for every platform — platforms change APIs and break extractors regularly.
- No bulk/automated scraping or archiving of third-party accounts' content.
- Not a redistribution tool — downloaded content is for private viewing only.

## 4. Target User

- Just you, personally. No multi-user support, no accounts, no auth layer.
- Accessed from your phone (and any other device) via a public URL (frontend on Netlify, backend on Render), since there's no private network/VPN involved.
- **Important tradeoff:** because there's no auth and the backend URL is publicly reachable on the internet (Render assigns a public URL by default), anyone who discovers/guesses the URL could technically use it. Since you're the only intended user, the practical mitigation is: (a) don't share or publish the URL, (b) rely on rate limiting (§9) to cap abuse if it ever leaks, (c) optionally add a trivial shared-secret header later if abuse becomes a problem — but this is not required for v1 per your requirements.

---

## 5. Legal & Compliance Notes (must read before building)

- Most platforms' Terms of Service prohibit downloading content via unofficial tools, regardless of "personal use" framing. This tool operates in a legal gray area in many jurisdictions.
- Do not deploy this publicly or distribute it to unknown users — that meaningfully increases legal exposure (copyright, ToS, anti-circumvention laws like DMCA §1201 in the US for DRM content).
- Recommended safeguards baked into the product:
  - Restrict to localhost / private network / authenticated access only.
  - Add a one-time acknowledgment screen: "I confirm I have rights to download this content or it is for personal archival/fair use purposes only."
  - Do not support DRM-protected sources (e.g., paid Netflix-style content) — out of scope entirely.
  - No public deployment, no ads, no monetization — this PRD assumes strictly personal use.

---

## 6. Supported Platforms (v1 target)

| Platform | Extraction feasibility | Notes |
|---|---|---|
| YouTube | High | Well supported by yt-dlp |
| TikTok | High | Supported, breaks occasionally with TikTok updates |
| Twitter/X | High | Supported, may need auth cookies for some content |
| Facebook | Medium | Public videos only reliably; private/login-walled content unreliable |
| Instagram | Medium | Often requires logged-in session cookies for reliable extraction |
| Pinterest | Medium | Pin videos/images generally extractable |
| Snapchat | Low-Medium | Public "Spotlight"/story content only; most content is ephemeral/private and not extractable |
| Threads | Low-Medium | Newer platform, extractor support varies/lags |
| Dailymotion | High | Well supported |

**Note:** Extraction reliability depends entirely on the underlying engine's (yt-dlp) current extractor support. Scope is limited to **publicly viewable content only** — no login/cookie support, so Instagram/Facebook/Snapchat/Threads content that requires a logged-in session simply won't resolve, and that's expected/acceptable behavior, not a bug.

---

## 7. Architecture

```
┌─────────────────┐        HTTPS/JSON         ┌───────────────────────┐
│   SPA Frontend    │ ────────────────────────▶ │   Backend API          │
│  (React/Vite)     │ ◀──────────────────────── │  (Node.js or Python)   │
└─────────────────┘                             └──────────┬────────────┘
                                                            │
                                                    invokes │
                                                            ▼
                                                 ┌───────────────────────┐
                                                 │  yt-dlp (subprocess/   │
                                                 │  library binding)      │
                                                 └──────────┬────────────┘
                                                            │
                                                            ▼
                                                  Target platform's
                                                  public/internal APIs
```

### 7.1 Frontend (SPA)
- Framework: React (Vite) or plain HTML/JS — no framework requirement, kept simple.
- Responsibilities:
  - URL input + platform auto-detection (regex match on domain).
  - Calls backend `/api/resolve` to get available formats/qualities.
  - Presents format/quality picker (e.g., 1080p MP4, audio-only MP3).
  - Calls backend `/api/download`, streams the response, and **automatically triggers the browser's native file download** (no manual "save" step, no intermediate history/list view).
  - No download history, no local storage of past downloads — fully stateless UI. Each visit is a clean slate.
  - No sensitive logic or platform credentials stored client-side.

### 7.2 Backend
- Framework: **Python + FastAPI**, using the **`yt-dlp` Python module directly** (`import yt_dlp`) rather than shelling out to the CLI — cleaner error handling, no subprocess management, easier to deploy on Render.
- Responsibilities:
  - Wraps `yt_dlp.YoutubeDL` as the extraction engine.
  - Exposes REST endpoints (see §8).
  - **No server-side file storage at all** — media is streamed from the source directly through the backend to the client in a single pass (pipe the extracted stream URL's bytes straight into the HTTP response). Nothing touches disk.
  - Rate limiting only (e.g., `slowapi` — a FastAPI-friendly wrapper around `limits`), no authentication.
  - Logging for debugging extractor failures only (URLs/errors, never downloaded content).

> **On 1080p and "no disk storage":** Higher-resolution formats (1080p and above) are usually served by platforms as *separate* video and audio streams that normally get merged with `ffmpeg` into one file. To get 1080p **without writing to disk**, the backend will run `ffmpeg` as a subprocess with its **input and output connected via pipes** (not files): the video stream and audio stream are piped in concurrently, `ffmpeg` muxes them in memory/in-transit, and the muxed output is piped straight to the HTTP response as it's produced — never touching the server's disk. This is standard `ffmpeg pipe:0`/`pipe:1` usage.
>
> **Tradeoff to flag:** this pipeline holds data in RAM/in-flight rather than on disk, and Render's free tier has limited memory (typically 512 MB). A long 1080p video could theoretically strain that limit. Mitigation: if a video's estimated size exceeds a safe threshold (e.g., ~150–200 MB), the backend can automatically fall back to a lower resolution rather than risk an out-of-memory crash. This threshold should be configurable and revisited once real usage patterns are known.

### 7.3 Why a backend is required
Browsers enforce CORS; none of the target platforms expose CORS-permissive endpoints for arbitrary origins, and reliable extraction requires spoofed headers, tokens, and platform-specific logic that must run server-side. A "no-backend" version is not feasible for this feature set (see prior discussion).

---

## 8. API Design (v1)

### `POST /api/resolve`
Request:
```json
{ "url": "https://www.tiktok.com/@user/video/12345" }
```
Response:
```json
{
  "platform": "tiktok",
  "title": "Video title",
  "thumbnail": "https://...",
  "duration": 34,
  "formats": [
    { "id": "hd", "label": "1080p MP4", "ext": "mp4", "filesize_approx": 8200000 },
    { "id": "sd", "label": "480p MP4", "ext": "mp4", "filesize_approx": 3100000 },
    { "id": "audio", "label": "Audio only (M4A)", "ext": "m4a", "filesize_approx": 900000 }
  ]
}
```

### `POST /api/download`
Request:
```json
{ "url": "https://www.tiktok.com/@user/video/12345", "formatId": "hd" }
```
Response: a **chunked HTTP stream** (`Transfer-Encoding: chunked`, `Content-Disposition: attachment; filename="..."`) — the server flushes bytes to the client as soon as they're produced by the extraction/merge pipeline, rather than assembling the full file first. Combined with the frontend using the native `fetch` + streamed `<a download>`/blob approach (or simply pointing the browser directly at this endpoint), this means **the download genuinely starts the moment the first bytes arrive** — there is no "download to the website, then download to your phone" double-hop. The website is never a full intermediate copy; it's a live pass-through.

For 1080p (merged) formats specifically: the pipeline is `platform source → ffmpeg (piped in/out) → HTTP response`, so the same instant-start behavior applies — ffmpeg begins emitting muxed output well before the entire source has arrived.

### `GET /api/health`
Simple liveness check for the extraction engine (e.g., confirms yt-dlp binary is present and its version).

### Error handling
Standardized error shape:
```json
{ "error": "UNSUPPORTED_URL" | "EXTRACTION_FAILED" | "PRIVATE_CONTENT" | "RATE_LIMITED", "message": "..." }
```

---

## 9. Security & Access Control

Per your requirements, there is **no authentication**. Since the backend will be publicly reachable (Render free tier gives a public URL), the following controls take the place of auth:

- **Rate limiting** on the backend (e.g., `slowapi`): cap requests per IP (e.g., 10 requests/minute, 100/day) to prevent your Render free-tier hours/bandwidth from being exhausted if the URL ever leaks or gets scanned by bots.
- **CORS lock-down**: only allow requests from your specific Netlify domain (e.g., `https://your-app.netlify.app`), not `*`. This doesn't stop direct API calls (e.g., via curl) but blocks casual browser-based abuse from other sites.
- **Input validation**: strict allow-list of supported platform domains before passing any URL into `yt_dlp` — rejects anything that isn't a recognized platform URL, which also closes off SSRF-style abuse (someone passing an internal/arbitrary URL to your server to make it fetch things on their behalf).
- **No shell interpolation**: use `yt_dlp` as a Python library call (`YoutubeDL(...).extract_info(...)`), never raw shell/subprocess string building.
- **No persistent storage**: nothing is written to disk, so there's no media library to secure or leak.
- No third-party analytics or telemetry in the frontend.
- This is a reasonable security posture for "just me, don't expect anyone to find it" — but be aware it's not equivalent to real access control. If misuse becomes a problem, the lowest-effort upgrade is a single shared-secret header (e.g., `X-App-Key`) checked on the backend — still not "auth" in the login sense, just a shared password baked into the frontend.

---

## 10. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Extraction response time | < 5s typical (network/platform dependent) |
| Download start latency | < 2s after format selection |
| Concurrent downloads | 1 (personal, single-user scale) |
| Frontend deployment | Netlify (free tier) — static React/Vite build |
| Backend deployment | Render (free tier) — Python web service |
| Access | Any device with the Netlify URL, including your phone browser |
| Uptime | Best-effort; **Render free tier spins down after ~15 min of inactivity**, so the first request after idle time will be slow (cold start, often 30–60s) — expect this on your phone if you haven't used it recently |
| Request timeout | Render free tier enforces request timeouts (historically ~30s+ depending on plan) — large/long videos streamed end-to-end may risk hitting this; keep in mind as a constraint, not just a nice-to-have |

---

## 11. Tech Stack Recommendation

- **Frontend:** React + Vite + Tailwind (or plain HTML/CSS/JS) — deployed to **Netlify** (free tier, static hosting + CDN, works great on mobile).
- **Backend:** **Python + FastAPI**, deployed to **Render** (free tier Web Service). Uses the `yt-dlp` **Python module** directly — no CLI shelling.
- **Extraction engine:** `yt-dlp` (actively maintained fork of youtube-dl, broadest platform support) — installed as a Python dependency (`pip install yt-dlp`).
- **Rate limiting:** `slowapi` (FastAPI-compatible rate limiter).
- **Media processing:** `ffmpeg` only if you later decide to support merged high-quality formats; not required for the "prefer progressive formats" v1 approach in §7.2.
- **Storage:** None. No database, no disk persistence, no Docker required — Render can run the FastAPI app directly from a `requirements.txt` + start command.

---

## 12. User Flow

1. You open the Netlify-hosted SPA on your phone (bookmark it for quick access).
2. Paste a URL and tap "Fetch". (If the Render backend was idle, expect a cold-start delay of up to ~60s on the first request.)
3. Frontend calls `/api/resolve`; backend uses `yt_dlp.YoutubeDL().extract_info(url, download=False)` to get metadata.
4. UI shows title, thumbnail, and available quality/format options (progressive formats only, per §7.2).
5. You select a format and tap "Download".
6. Backend opens the source stream and pipes bytes directly through the HTTP response to your phone — nothing is written to disk on the server.
7. Your phone's browser automatically saves the file to Downloads as soon as streaming completes — no extra tap needed.

---

## 13. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Platform changes break extraction | Pin `yt-dlp` version, update regularly via `pip install -U yt-dlp`; isolate extraction behind a service interface |
| Legal/ToS exposure | Keep the URL private/unshared, personal use only, add usage disclaimer in the UI |
| No auth + public URL means anyone who finds it can use your backend | Rate limiting (§9) caps damage; keep the Netlify URL unshared/unindexed (add `robots.txt` disallow-all) |
| Render free tier cold starts / sleep | Accept the delay as a known tradeoff, or occasionally "warm" it by opening the app before you need it |
| Render free tier RAM limits (~512 MB) during in-memory ffmpeg merge for 1080p | Estimate size before merging (yt-dlp reports approximate filesize); auto-fallback to a lower resolution if a video is likely too large; monitor for OOM in logs and adjust the threshold |
| Render free tier request timeout on very long videos | Chunked/streamed responses reduce this risk since data flows continuously rather than waiting at the end, but extremely long videos may still be affected; if frequent, this signals a plan upgrade rather than a code fix |
| Streaming large 1080p files through a free-tier backend consumes bandwidth quota fast | Rate limiting + personal-only usage keeps this manageable; monitor Render's usage dashboard |

---

## 14. Milestones

**Phase 1 — MVP**
- FastAPI backend wrapping `yt_dlp` (Python module) for YouTube, TikTok, X/Twitter, Dailymotion — **public content only**.
- 1080p support via in-memory `ffmpeg` pipe-based merge (no disk writes), with automatic fallback to lower resolution if estimated size risks Render's free-tier RAM limit.
- Chunked/streamed responses so downloads start instantly on the client, no double-hop buffering.
- Basic SPA: URL input, resolve, format list, tap-to-download (auto-saves).
- Rate limiting on backend.
- Deploy frontend to Netlify, backend to Render (free tiers).

**Phase 2 — Expanded platform support**
- Add Instagram, Facebook, Pinterest, Threads, Snapchat (public content, best-effort).

**Phase 3 — Polish**
- Batch URL input (multiple links at once).
- Audio-only extraction shortcut (MP3).
- Tune the memory-safety threshold for 1080p merging based on real-world Render usage data.

---

## 15. Open Questions (remaining)

- What size/duration threshold should trigger the "fall back to lower resolution" safety net for the in-memory 1080p merge (needs real testing on Render's actual free-tier RAM)?
- What rate limit numbers feel right for solo use without being annoying (e.g., 10/min, 100/day as a starting guess)?
- If Render free-tier timeouts or memory limits turn out to be frequent blockers, is upgrading to a paid tier acceptable, or should we cap supported video length/resolution instead?

**Already decided (per your input):** no auth, no persistent server-side storage (in-memory pipe-based merge only, for 1080p), no download history, single user, public content only, Python/FastAPI + yt-dlp backend, instant/chunked-stream downloads (no double-hop buffering), Netlify + Render free-tier deployment.
