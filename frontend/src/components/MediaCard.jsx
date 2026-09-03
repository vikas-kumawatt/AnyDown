import { formatBytes, formatDuration } from '../format.js'
import { MetaRow, QualityCard } from './Primitives.jsx'

const NOTE = {
  best: 'Recommended — best available',
  merge: 'Video + audio, combined server-side',
  audio: 'Audio only',
  image: 'Still image',
  progressive: 'Single stream',
}

export function MediaCard({ media, onDownload, startedId }) {
  const duration = formatDuration(media.duration)

  return (
    <section>
      <div className="aspect-video overflow-hidden rounded-card bg-surface">
        {media.thumbnail ? (
          <img
            src={media.thumbnail}
            alt=""
            loading="lazy"
            referrerPolicy="no-referrer"
            className="h-full w-full object-cover"
            onError={(event) => {
              event.currentTarget.style.display = 'none'
            }}
          />
        ) : (
          <div className="flex h-full items-center justify-center">
            <img src="/icon.png" alt="" width="52" height="52" className="opacity-35" />
          </div>
        )}
      </div>

      <h2 className="mt-[18px] text-[19px] font-bold leading-[26px] tracking-[-0.3px]">
        {media.title}
      </h2>

      <div className="mt-3 rounded-card bg-surface px-4 py-1.5">
        {media.uploader && <MetaRow label="Source" value={media.uploader} />}
        {duration && <MetaRow label="Length" value={duration} />}
        <MetaRow label="Options" value={String(media.formats.length)} />
      </div>

      <h3 className="label mt-7 tracking-[1.6px] text-ink">Choose quality</h3>

      <div className="mt-3 space-y-2.5">
        {media.formats.map((format) => (
          <QualityCard
            key={format.id}
            headline={format.label}
            note={NOTE[format.kind] || NOTE.progressive}
            trailing={
              startedId === format.id
                ? 'Starting…'
                : formatBytes(format.filesize_approx) || '—'
            }
            onClick={() => onDownload(format)}
          />
        ))}
      </div>

      <p className="mt-4 text-[13px] leading-5 text-ink-3">
        Sizes are the platform&apos;s estimate. The file streams straight to your
        device — nothing is stored on the server.
      </p>
    </section>
  )
}
