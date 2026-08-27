import { formatBytes, formatDuration } from '../format.js'

const KIND_NOTE = {
  merge: 'merged on the fly',
  audio: 'audio only',
}

export function MediaCard({ media, onDownload, startedId }) {
  const duration = formatDuration(media.duration)

  return (
    <section className="overflow-hidden rounded-2xl border border-edge bg-panel/70 backdrop-blur">
      {media.thumbnail && (
        <img
          src={media.thumbnail}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          className="aspect-video w-full bg-ink object-cover"
          onError={(event) => {
            event.currentTarget.style.display = 'none'
          }}
        />
      )}

      <div className="p-4">
        <h2 className="text-base font-semibold leading-snug">{media.title}</h2>
        <p className="mt-1 text-xs text-slate-400">
          {[media.uploader, duration, media.platform].filter(Boolean).join(' · ')}
        </p>

        <ul className="mt-4 space-y-2">
          {media.formats.map((format) => {
            const size = formatBytes(format.filesize_approx)
            const note = KIND_NOTE[format.kind]
            return (
              <li key={format.id}>
                <button
                  type="button"
                  onClick={() => onDownload(format)}
                  className="flex w-full items-center justify-between gap-3 rounded-xl border border-edge bg-ink/50 px-4 py-3 text-left transition hover:border-accent active:brightness-95"
                >
                  <span className="min-w-0">
                    <span className="block font-medium">{format.label}</span>
                    {note && (
                      <span className="block text-xs text-slate-500">{note}</span>
                    )}
                  </span>
                  <span className="shrink-0 text-sm text-slate-400">
                    {startedId === format.id ? 'Starting…' : size || '—'}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>

        <p className="mt-4 text-xs leading-relaxed text-slate-500">
          Sizes are estimates from the platform. The file streams straight to your
          device — nothing is stored on the server.
        </p>
      </div>
    </section>
  )
}
