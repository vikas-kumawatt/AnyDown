// Parity check: the client allow-list must agree with the backend's
// (backend/app/platforms.py). These are the same cases as
// backend/tests/test_platforms.py, so the two can't silently drift.
//
// Plain Node, no build step: `npm test`.

import { detectPlatform, PLATFORMS } from '../src/platforms.js'
import { formatBytes, formatDuration } from '../src/format.js'

let failures = 0

function eq(name, got, want) {
  if (got !== want) {
    console.error(`FAIL ${name}: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`)
    failures += 1
  }
}

const ALLOWED = [
  ['https://www.youtube.com/watch?v=abc', 'youtube'],
  ['https://www.reddit.com/r/x/comments/1/y/', 'reddit'],
  ['https://v.redd.it/abc', 'reddit'],
  ['https://vimeo.com/22439234', 'vimeo'],
  ['https://vk.com/video-1_2', 'vk'],
  ['https://www.linkedin.com/posts/x', 'linkedin'],
  ['https://www.twitch.tv/videos/1', 'twitch'],
  ['https://soundcloud.com/a/b', 'soundcloud'],
  ['https://ok.ru/video/1', 'ok'],
  ['https://terabox.com/s/1abc', 'terabox'],
  ['https://www.4funbox.com/s/1abc', 'terabox'],
  ['https://youtu.be/abc', 'youtube'],
  ['https://m.youtube.com/watch?v=abc', 'youtube'],
  ['https://www.tiktok.com/@u/video/1', 'tiktok'],
  ['https://vm.tiktok.com/ZM123/', 'tiktok'],
  ['https://x.com/u/status/1', 'twitter'],
  ['https://notthreads.net/x', null],
  ['https://twitter.com/u/status/1', 'twitter'],
  ['https://www.dailymotion.com/video/x1', 'dailymotion'],
  ['https://dai.ly/x1', 'dailymotion'],
  ['https://www.instagram.com/reel/abc/', 'instagram'],
  ['https://www.facebook.com/watch?v=1', 'facebook'],
  ['https://fb.watch/abc/', 'facebook'],
  ['https://www.pinterest.com/pin/1/', 'pinterest'],
  ['https://pinterest.co.uk/pin/1/', 'pinterest'],
  ['https://pin.it/abc', 'pinterest'],
  ['https://www.threads.net/@u/post/1', 'threads'],
  ['https://www.snapchat.com/spotlight/abc', 'snapchat'],
]

// Look-alike hosts must never match by substring.
const REJECTED = [
  'https://evil.com/video',
  'http://localhost:8080/admin',
  'http://169.254.169.254/latest/meta-data/',
  'file:///etc/passwd',
  'ftp://youtube.com/x',
  'https://youtube.com.evil.com/watch?v=1',
  'https://notyoutube.com/watch?v=1',
  'https://tiktok.com.attacker.net/v/1',
  'https://terabox.com.evil.net/s/1abc',
  '',
  'not a url',
]

for (const [url, id] of ALLOWED) eq(`allow ${url}`, detectPlatform(url)?.id ?? null, id)
for (const url of REJECTED) eq(`reject ${url}`, detectPlatform(url), null)

eq('trailing dot host', detectPlatform('https://www.youtube.com./watch?v=abc')?.id, 'youtube')
eq('platform count', PLATFORMS.length, 18)

eq('bytes: zero is hidden', formatBytes(0), null)
eq('bytes: mb', formatBytes(8200000), '7.8 MB')
eq('bytes: raw', formatBytes(512), '512 B')
eq('duration: seconds', formatDuration(34), '0:34')
eq('duration: minutes', formatDuration(212), '3:32')
eq('duration: hours', formatDuration(3725), '1:02:05')
eq('duration: zero is hidden', formatDuration(0), null)

const total = ALLOWED.length + REJECTED.length + 8
console.log(failures === 0 ? `ok - ${total} checks passed` : `${failures} failed`)
process.exit(failures === 0 ? 0 : 1)
