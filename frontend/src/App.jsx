import { useCallback, useEffect, useRef, useState } from 'react'
import { downloadUrl, resolveMedia } from './api.js'
import { Acknowledgement, hasAcknowledged } from './components/Acknowledgement.jsx'
import { MediaCard } from './components/MediaCard.jsx'
import { Notice } from './components/Notice.jsx'
import { UrlForm } from './components/UrlForm.jsx'

// Render's free tier sleeps after ~15 minutes, so the first request can take
// most of a minute. Say so rather than looking broken (PRD section 10).
const COLD_START_HINT_MS = 6000

export default function App() {
  const [acknowledged, setAcknowledged] = useState(hasAcknowledged)
  const [url, setUrl] = useState('')
  const [media, setMedia] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [slow, setSlow] = useState(false)
  const [startedId, setStartedId] = useState(null)

  const abortRef = useRef(null)
  const timersRef = useRef([])

  const clearTimers = useCallback(() => {
    timersRef.current.forEach(clearTimeout)
    timersRef.current = []
  }, [])

  useEffect(() => {
    return () => {
      abortRef.current?.abort()
      clearTimers()
    }
  }, [clearTimers])

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
      setError(err.message)
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
      // manager, so the file never passes through JS memory. target=_blank
      // (rather than navigating this tab) means a rare JSON error from the
      // endpoint opens in a throwaway tab instead of wiping the page state.
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
    return (
      <main className="flex min-h-dvh items-center justify-center p-4">
        <Acknowledgement onAccept={() => setAcknowledged(true)} />
      </main>
    )
  }

  return (
    <main className="mx-auto w-full max-w-md px-4 py-8">
      <header className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight">Downloader</h1>
        <p className="mt-1 text-sm text-slate-400">
          Public content only. Personal use.
        </p>
      </header>

      <UrlForm url={url} onUrlChange={setUrl} onSubmit={fetchMedia} busy={busy} />

      <div className="mt-5 space-y-4">
        {slow && (
          <Notice>
            Still waiting — the server sleeps when idle and can take up to a
            minute to wake up.
          </Notice>
        )}

        {error && <Notice tone="error">{error}</Notice>}

        {media && (
          <MediaCard
            media={media}
            onDownload={startDownload}
            startedId={startedId}
          />
        )}
      </div>
    </main>
  )
}
