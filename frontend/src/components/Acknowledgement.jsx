// One-time rights acknowledgement (PRD section 5). The flag is the only thing
// this app ever puts in localStorage — no download history is kept.

const STORAGE_KEY = 'downloader.rights-ack.v1'

export function hasAcknowledged() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'yes'
  } catch {
    // Private browsing can throw on access; just ask again this session.
    return false
  }
}

export function Acknowledgement({ onAccept }) {
  const accept = () => {
    try {
      localStorage.setItem(STORAGE_KEY, 'yes')
    } catch {
      /* non-fatal: the gate simply reappears next visit */
    }
    onAccept()
  }

  return (
    <div className="mx-auto w-full max-w-md rounded-2xl border border-edge bg-panel/80 p-6 shadow-xl backdrop-blur">
      <h1 className="text-xl font-semibold">Before you start</h1>
      <p className="mt-3 text-sm leading-relaxed text-slate-300">
        This tool is for personal use only. Downloading may breach a platform's
        terms of service, and most content is protected by copyright.
      </p>
      <ul className="mt-4 space-y-2 text-sm text-slate-400">
        <li>• Public content only — nothing private or login-walled.</li>
        <li>• No DRM-protected or paid content.</li>
        <li>• For private viewing. Not for redistribution.</li>
      </ul>
      <button
        type="button"
        onClick={accept}
        className="mt-6 w-full rounded-xl bg-accent px-4 py-3 font-medium text-white transition hover:brightness-110 active:brightness-95"
      >
        I have the right to download this content
      </button>
    </div>
  )
}
