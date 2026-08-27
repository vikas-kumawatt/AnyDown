import { useEffect, useRef, useState } from 'react'
import { detectPlatform, PLATFORMS } from '../platforms.js'

export function UrlForm({ url, onUrlChange, onSubmit, busy }) {
  const [canPaste, setCanPaste] = useState(false)
  const inputRef = useRef(null)

  useEffect(() => {
    // Only offer the paste shortcut where the Clipboard API actually exists
    // (it needs a secure context and is missing on some mobile browsers).
    setCanPaste(Boolean(navigator.clipboard?.readText))
  }, [])

  const platform = url.trim() ? detectPlatform(url) : null
  const unknown = url.trim().length > 8 && !platform

  const paste = async () => {
    try {
      const text = await navigator.clipboard.readText()
      if (text) {
        onUrlChange(text.trim())
        inputRef.current?.focus()
      }
    } catch {
      // Permission denied — the user can still paste manually.
      setCanPaste(false)
    }
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (!busy && url.trim()) onSubmit()
      }}
    >
      <label htmlFor="url" className="text-sm font-medium text-slate-300">
        Paste a link
      </label>

      <div className="mt-2 flex gap-2">
        <input
          id="url"
          ref={inputRef}
          type="url"
          inputMode="url"
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="none"
          spellCheck={false}
          enterKeyHint="go"
          placeholder="https://…"
          value={url}
          onChange={(event) => onUrlChange(event.target.value)}
          className="min-w-0 flex-1 rounded-xl border border-edge bg-ink/60 px-4 py-3 outline-none transition placeholder:text-slate-600 focus:border-accent"
        />
        {canPaste && (
          <button
            type="button"
            onClick={paste}
            className="shrink-0 rounded-xl border border-edge px-4 py-3 text-sm text-slate-300 transition hover:border-accent hover:text-white"
          >
            Paste
          </button>
        )}
      </div>

      <div className="mt-2 h-5 text-xs">
        {platform && <span className="text-accent">Detected: {platform.label}</span>}
        {unknown && (
          <span className="text-amber-400">
            Not a recognised platform link — the server will reject it.
          </span>
        )}
      </div>

      <button
        type="submit"
        disabled={busy || !url.trim()}
        className="mt-3 w-full rounded-xl bg-accent px-4 py-3 font-medium text-white transition hover:brightness-110 active:brightness-95 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {busy ? 'Fetching…' : 'Fetch'}
      </button>

      <p className="mt-4 text-center text-xs leading-relaxed text-slate-600">
        {PLATFORMS.map((p) => p.label).join(' · ')}
      </p>
    </form>
  )
}
