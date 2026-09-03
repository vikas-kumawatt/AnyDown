// Recognises the site a link belongs to, for labelling.
//
// Not a restriction — an unlisted host still works, it just shows no label.
// Kept in step with backend/app/platforms.py and
// android/.../domain/Platforms.kt.

export const PLATFORMS = [
  { id: 'youtube', label: 'YouTube', domains: ['youtube.com', 'youtu.be', 'youtube-nocookie.com'] },
  { id: 'tiktok', label: 'TikTok', domains: ['tiktok.com'] },
  { id: 'twitter', label: 'X', domains: ['twitter.com', 'x.com', 't.co'] },
  { id: 'instagram', label: 'Instagram', domains: ['instagram.com', 'instagr.am', 'ig.me'] },
  { id: 'facebook', label: 'Facebook', domains: ['facebook.com', 'fb.watch', 'fb.com'] },
  { id: 'snapchat', label: 'Snapchat', domains: ['snapchat.com'] },
  { id: 'threads', label: 'Threads', domains: ['threads.net', 'threads.com'] },
  { id: 'reddit', label: 'Reddit', domains: ['reddit.com', 'redd.it', 'redditmedia.com'] },
  { id: 'vimeo', label: 'Vimeo', domains: ['vimeo.com'] },
  { id: 'dailymotion', label: 'Dailymotion', domains: ['dailymotion.com', 'dai.ly'] },
  { id: 'vk', label: 'VK', domains: ['vk.com', 'vkvideo.ru', 'vk.ru'] },
  { id: 'linkedin', label: 'LinkedIn', domains: ['linkedin.com', 'lnkd.in'] },
  { id: 'pinterest', label: 'Pinterest', domains: ['pinterest.com', 'pin.it'] },
  { id: 'twitch', label: 'Twitch', domains: ['twitch.tv'] },
  { id: 'tumblr', label: 'Tumblr', domains: ['tumblr.com'] },
  { id: 'soundcloud', label: 'SoundCloud', domains: ['soundcloud.com', 'snd.sc'] },
  { id: 'ok', label: 'OK.ru', domains: ['ok.ru', 'odnoklassniki.ru'] },
  {
    id: 'terabox',
    label: 'TeraBox',
    domains: [
      'terabox.com', 'terabox.app', '1024terabox.com', 'teraboxapp.com',
      'teraboxlink.com', 'terasharelink.com', 'teraboxshare.com',
      '4funbox.com', 'mirrobox.com', 'nephobox.com', 'momerybox.com',
      'tibibox.com', 'freeterabox.com', 'terafileshare.com',
    ],
  },
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

  const named = PLATFORMS.find((platform) =>
    platform.domains.some(
      // Exact match or a real subdomain, so look-alike hosts such as
      // "youtube.com.evil.com" never match.
      (domain) => bare === domain || bare.endsWith('.' + domain),
    ),
  )
  if (named) return named

  // Pinterest also serves country TLDs: pinterest.co.uk, pinterest.de, ...
  if (bare.startsWith('pinterest.') && (bare.match(/\./g) || []).length <= 2) {
    return PLATFORMS.find((p) => p.id === 'pinterest')
  }
  return null
}
