// Shared pieces, mirroring android/.../ui/Components.kt.

const TONE = {
  neutral: 'bg-ink-2',
  error: 'bg-danger',
  success: 'bg-success',
  warning: 'bg-warning',
}

/** Solid near-white slab that presses in slightly. */
export function PrimaryButton({ label, loadingLabel, loading, disabled, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || loading}
      className="flex h-14 w-full items-center justify-center gap-3 rounded-[14px]
                 bg-accent font-bold tracking-[0.2px] text-on-accent transition
                 active:scale-[0.975] disabled:bg-raised disabled:text-ink-3
                 disabled:active:scale-100"
    >
      {loading && (
        <span
          className="h-4 w-4 animate-spin rounded-full border-2 border-on-accent
                     border-t-transparent"
          aria-hidden="true"
        />
      )}
      {loading ? loadingLabel || label : label}
    </button>
  )
}

/** Bordered secondary action, sized to its label. */
export function GhostButton({ label, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="label rounded-[10px] border border-edge px-3.5 py-2.5
                 text-ink-2 transition hover:bg-pressed"
    >
      {label}
    </button>
  )
}

/** Circular close button, used for clearing the input and dismissing notices. */
export function CloseButton({ onClick, label = 'Clear' }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      className="flex h-[26px] w-[26px] shrink-0 items-center justify-center
                 rounded-full bg-raised text-ink-2 transition hover:bg-pressed"
    >
      <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
        <path
          d="M1 1l8 8M9 1L1 9"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
        />
      </svg>
    </button>
  )
}

/** Status dot plus label, for the header. */
export function StatusChip({ label, tone = 'neutral' }) {
  return (
    <span className="flex items-center gap-[7px] rounded-full bg-surface px-[11px] py-1.5">
      <span className={`h-[5px] w-[5px] rounded-full ${TONE[tone]}`} aria-hidden="true" />
      <span className="label">{label}</span>
    </span>
  )
}

/**
 * A message on a raised surface with a small status dot.
 *
 * The dot is the only saturated colour; a fully tinted panel would shout on a
 * dark page.
 */
export function Notice({ tone = 'neutral', children, detail, onDismiss }) {
  return (
    <div className="rounded-card bg-surface p-4">
      <div className="flex items-start gap-3">
        <span
          className={`mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full ${TONE[tone]}`}
          aria-hidden="true"
        />
        <div className="flex-1 text-[13px] leading-5 text-ink">{children}</div>
        {onDismiss && <CloseButton onClick={onDismiss} label="Dismiss" />}
      </div>
      {detail && (
        <details className="mt-2.5">
          <summary className="label cursor-pointer list-none text-ink-2">
            Show details
          </summary>
          {/* The server's own words, verbatim. When a platform breaks this is
              the only useful thing on screen. */}
          <pre className="mt-2.5 overflow-x-auto rounded-[10px] bg-base p-3
                          font-mono text-[11px] leading-[17px] text-ink-2">
            {detail}
          </pre>
        </details>
      )}
    </div>
  )
}

/** Label left, value right. */
export function MetaRow({ label, value }) {
  return (
    <div className="flex items-center justify-between py-2.5">
      <span className="label">{label}</span>
      <span className="truncate pl-6 text-[13px] text-ink">{value}</span>
    </div>
  )
}

/** A quality row: a tappable card, not a table line. */
export function QualityCard({ headline, note, trailing, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-3.5 rounded-card bg-surface px-4 py-[15px]
                 text-left transition hover:bg-pressed active:bg-pressed"
    >
      <span className="min-w-0 flex-1">
        <span className="figure block text-[17px] font-bold tracking-[-0.2px] text-ink">
          {headline}
        </span>
        <span className="mt-[3px] block text-[13px] text-ink-3">{note}</span>
      </span>
      <span className="figure shrink-0 font-mono text-xs text-ink-2">{trailing}</span>
      <span
        className="flex h-[30px] w-[30px] shrink-0 items-center justify-center
                   rounded-full bg-raised"
        aria-hidden="true"
      >
        <svg width="13" height="13" viewBox="0 0 13 13">
          <path
            d="M6.5 1v10M2.4 6.8l4.1 4.2 4.1-4.2"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            fill="none"
            className="text-ink"
          />
        </svg>
      </span>
    </button>
  )
}
