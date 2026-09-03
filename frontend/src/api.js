const API_BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/+$/, '')
const APP_KEY = import.meta.env.VITE_APP_KEY || ''

// Messages the UI shows for each backend error code. Anything unrecognised
// falls back to the server's own message.
const FRIENDLY = {
  UNSUPPORTED_URL: "That link can't be used — check it's a public http(s) address.",
  NEEDS_FFMPEG:
    'This link only offers separate video and audio tracks, which the server ' +
    "needs ffmpeg to combine — and ffmpeg isn't available.",
  PRIVATE_CONTENT:
    'That content is private or login-walled. Only public content works.',
  RATE_LIMITED: 'Too many requests. Wait a minute and try again.',
  NO_FORMATS: 'Nothing downloadable was found at that link.',
  BUSY: 'A download is already running. Try again in a moment.',
  UPSTREAM_ERROR: 'The platform refused the request. Try fetching again.',
}

export class ApiError extends Error {
  constructor(code, message, detail) {
    super(message)
    this.code = code
    // The server's own words, shown behind "Show details". A polished lie is
    // worse than the raw line when a platform breaks.
    this.detail = detail
  }
}

export async function resolveMedia(url, signal) {
  let response
  try {
    response = await fetch(`${API_BASE}/api/resolve`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(APP_KEY ? { 'X-App-Key': APP_KEY } : {}),
      },
      body: JSON.stringify({ url }),
      signal,
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new ApiError(
      'NETWORK',
      "Couldn't reach the server. If it was asleep, give it a minute and retry.",
    )
  }

  let body = null
  try {
    body = await response.json()
  } catch {
    /* fall through to the generic error below */
  }

  if (!response.ok) {
    const code = body?.error || 'EXTRACTION_FAILED'
    throw new ApiError(
      code,
      FRIENDLY[code] || body?.message || 'Something went wrong.',
      // Only worth surfacing when it says more than the friendly line does.
      FRIENDLY[code] ? body?.message : undefined,
    )
  }
  return body
}

// A plain GET the browser can navigate to, so the file goes straight to the
// device's download manager. Fetching it into a Blob first would buffer the
// whole video in memory — the double-hop the PRD rules out.
export function downloadUrl(url, formatId) {
  const params = new URLSearchParams({ url, formatId })
  if (APP_KEY) params.set('k', APP_KEY)
  return `${API_BASE}/api/download?${params.toString()}`
}

export async function checkHealth(signal) {
  const response = await fetch(`${API_BASE}/api/health`, { signal })
  if (!response.ok) throw new ApiError('HEALTH', 'Backend is not healthy.')
  return response.json()
}
