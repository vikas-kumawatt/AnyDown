import { useCallback, useEffect, useRef, useState } from 'react'
import { checkHealth, downloadUrl, resolveMedia } from './api.js'
import { Acknowledgement, hasAcknowledged } from './components/Acknowledgement.jsx'
import { MediaCard } from './components/MediaCard.jsx'
import { Notice, StatusChip } from './components/Primitives.jsx'
import { UrlForm } from './components/UrlForm.jsx'

// Render's free tier sleeps after ~15 minutes, so the first request can take
// most of a minute. Say so rather than looking broken.
const COLD_START_HINT_MS = 6000

export default function App() {
  const [acknowledged, setAcknowledged] = useState(hasAcknowledged)
  const [url, setUrl] = useState('')
  const [media, setMedia] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [slow, setSlow] = useState(false)
  const [startedId, setStartedId] = useState(null)
  const [health, setHealth] = useState(null)

  const abortRef = useRef(null)
  const timersRef = useRef([])

  const clearTimers = useCallback(() => {
    timersRef.current.forEach(clearTimeout)
    timersRef.current = []
  }, [])

  useEffect(() => {
    // Mirrors the app's READY / LIMITED chip: without ffmpeg the server can't
    // combine separate video and audio, which caps quality.
    const controller = new AbortController()
    checkHealth(controller.signal)
      .then(setHealth)
      .catch(() => setHealth(null))
    return () => controller.abort()
  }, [])

  useEffect(
    () => () => {
      abortRef.current?.abort()
      clearTimers()
    },
    [clearTimers],
  )

  const fetchMedia = useCallback(async () => {
    abortRef.current?.abort()
    clearTimers()

    const controller = new AbortController()
    abortRef.current = controller

    setBusy(true)
    setSlow(false)
    setError(null)
    setMedia(null)
    setStartedId(null)

    timersRef.current.push(setTimeout(() => setSlow(true), COLD_START_HINT_MS))

    try {
      setMedia(await resolveMedia(url.trim(), controller.signal))
    } catch (err) {
      if (err.name === 'AbortError') return
      setError({ message: err.message, detail: err.detail })
    } finally {
      if (abortRef.current === controller) {
        setBusy(false)
        setSlow(false)
        clearTimers()
      }
    }
  }, [url, clearTimers])

  const startDownload = useCallback(
    (format) => {
      setStartedId(format.id)
      // A real anchor click hands the response to the browser's own download
      // manager, so the file never passes through JS memory.
      const anchor = document.createElement('a')
      anchor.href = downloadUrl(url.trim(), format.id)
      anchor.target = '_blank'
      anchor.rel = 'noopener'
      anchor.download = ''
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      timersRef.current.push(setTimeout(() => setStartedId(null), 8000))
    },
    [url],
  )

  if (!acknowledged) {
    return <Acknowledgement onAccept={() => setAcknowledged(true)} />
  }

  const status = !health
    ? { label: 'Connecting', tone: 'neutral' }
    : health.ffmpeg
      ? { label: 'Ready', tone: 'success' }
      : { label: 'Limited', tone: 'warning' }

  return (
    <main className="mx-auto w-full max-w-md px-5 pb-14">
      <header className="flex items-center gap-3 py-4">
        <img src="/icon.png" alt="" width="34" height="34" className="rounded-[10px]" />
        <span className="text-[19px] font-extrabold tracking-[-0.3px]">AnyDown</span>
        <span className="flex-1" />
        <StatusChip label={status.label} tone={status.tone} />
      </header>

      <div className="mt-5">
        <UrlForm url={url} onUrlChange={setUrl} onSubmit={fetchMedia} busy={busy} />
      </div>

      <div className="mt-4 space-y-4">
        {health && !health.ffmpeg && (
          <Notice tone="warning">
            ffmpeg isn&apos;t available on the server, so video and audio can&apos;t be
            combined. Quality is capped and some sites won&apos;t work at all.
          </Notice>
        )}

        {slow && (
          <Notice>
            Still waiting — the server sleeps when idle and can take up to a minute
            to wake up.
          </Notice>
        )}

        {error && (
          <Notice tone="error" detail={error.detail} onDismiss={() => setError(null)}>
            {error.message}
          </Notice>
        )}

        {media && (
          <div className="pt-6">
            <MediaCard media={media} onDownload={startDownload} startedId={startedId} />
          </div>
        )}
      </div>

      <p className="label mt-10">Public content only · Personal use</p>
    </main>
  )
}
