import { useState } from 'react'
import { detectPlatform } from '../platforms.js'
import { CloseButton, PrimaryButton } from './Primitives.jsx'

/**
 * The paste field, on its own raised surface with a clear button.
 *
 * A boxed input with a floating label is the most recognisable stock-form tell
 * there is, so this is borderless text on a surface instead.
 */
export function UrlForm({ url, onUrlChange, onSubmit, busy }) {
  const [focused, setFocused] = useState(false)
  const platform = url.trim() ? detectPlatform(url) : null

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (!busy && url.trim()) onSubmit()
      }}
    >
      <div
        className={`flex items-center gap-2.5 rounded-card bg-surface px-4 py-3.5
                    transition ${
                      focused ? 'ring-1 ring-edge-strong' : 'ring-1 ring-edge'
                    }`}
      >
        <input
          type="url"
          inputMode="url"
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="none"
          spellCheck={false}
          enterKeyHint="go"
          placeholder="Paste a link"
          aria-label="Paste a link"
          value={url}
          onChange={(event) => onUrlChange(event.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          className="min-w-0 flex-1 bg-transparent font-medium text-ink outline-none
                     placeholder:text-ink-3"
        />
        {url && <CloseButton onClick={() => onUrlChange('')} />}
      </div>

      <div className="mt-2.5 flex h-4 items-center gap-2">
        {platform && (
          <span className="h-[5px] w-[5px] rounded-full bg-success" aria-hidden="true" />
        )}
        <span
          className={`label tracking-[0.6px] ${
            platform ? 'text-ink-2' : 'text-ink-3'
          }`}
        >
          {platform
            ? platform.label
            : url.trim()
              ? 'Unrecognised site — will still try'
              : 'Any site yt-dlp supports'}
        </span>
      </div>

      <div className="mt-[18px]">
        <PrimaryButton
          label="Fetch"
          loadingLabel="Reading link"
          loading={busy}
          disabled={!url.trim()}
          onClick={onSubmit}
        />
      </div>
    </form>
  )
}
