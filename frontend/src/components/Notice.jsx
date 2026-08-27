const TONES = {
  error: 'border-rose-500/40 bg-rose-500/10 text-rose-200',
  info: 'border-accent/40 bg-accent/10 text-slate-200',
}

export function Notice({ tone = 'info', children }) {
  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      className={`rounded-xl border px-4 py-3 text-sm leading-relaxed ${TONES[tone]}`}
    >
      {children}
    </div>
  )
}
