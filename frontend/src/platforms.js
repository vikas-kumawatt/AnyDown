// Client-side platform detection (PRD 7.1). Mirrors the backend allow-list in
// app/platforms.py — this is a UX affordance only; the backend re-validates.

export const PLATFORMS = [
  { id: 'youtube', label: 'YouTube', domains: ['youtube.com', 'youtu.be'] },
  { id: 'tiktok', label: 'TikTok', domains: ['tiktok.com'] },
  { id: 'twitter', label: 'X / Twitter', domains: ['twitter.com', 'x.com', 't.co'] },
  { id: 'dailymotion', label: 'Dailymotion', domains: ['dailymotion.com', 'dai.ly'] },
  { id: 'instagram', label: 'Instagram', domains: ['instagram.com', 'instagr.am'] },
  { id: 'facebook', label: 'Facebook', domains: ['facebook.com', 'fb.watch', 'fb.com'] },
  { id: 'pinterest', label: 'Pinterest', domains: ['pinterest.', 'pin.it'] },
  { id: 'threads', label: 'Threads', domains: ['threads.net', 'threads.com'] },
  { id: 'snapchat', label: 'Snapchat', domains: ['snapchat.com'] },
]

export function detectPlatform(rawUrl) {
  let host
  try {
    const parsed = new URL(rawUrl.trim())
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null
    host = parsed.hostname.toLowerCase().replace(/\.$/, '')
  } catch {
    return null
  }

  const bare = host.startsWith('www.') ? host.slice(4) : host

  return (
    PLATFORMS.find((platform) =>
      platform.domains.some((domain) =>
        // "pinterest." is a prefix rule for country TLDs; the rest are exact
        // registered domains matched with a leading dot so look-alike hosts
        // such as "youtube.com.evil.com" never match.
        domain.endsWith('.')
          ? bare.startsWith(domain)
          : bare === domain || bare.endsWith('.' + domain),
      ),
    ) || null
  )
}
