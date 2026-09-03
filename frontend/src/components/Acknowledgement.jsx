import { PrimaryButton } from './Primitives.jsx'

// One-time rights acknowledgement. The flag is the only thing this app ever
// puts in localStorage — no download history is kept.

const STORAGE_KEY = 'downloader.rights-ack.v1'

export function hasAcknowledged() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'yes'
  } catch {
    // Private browsing can throw on access; just ask again this session.
    return false
  }
}

const RULES = [
  ['Public content only', 'Nothing private or login-walled.'],
  ['No DRM', 'Paid and protected content is out of scope.'],
  ['Private viewing', 'Never redistribution.'],
]

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
    <div className="mx-auto w-full max-w-md px-5 py-16">
      <img src="/icon.png" alt="" width="56" height="56" className="rounded-[14px]" />

      <h1 className="mt-9 text-[32px] font-extrabold leading-[39px] tracking-[-0.8px]">
        Before you
        <br />
        begin
      </h1>

      <p className="mt-[18px] text-[15px] leading-[23px] text-ink-2">
        This tool is for personal use. Downloading may breach a platform&apos;s terms
        of service, and most content is protected by copyright.
      </p>

      <div className="mt-8 space-y-2.5">
        {RULES.map(([heading, detail], index) => (
          <div key={heading} className="flex gap-4 rounded-card bg-surface p-4">
            <span className="figure pt-0.5 font-mono text-xs text-ink-3">
              0{index + 1}
            </span>
            <span>
              <span className="block font-medium text-ink">{heading}</span>
              <span className="mt-0.5 block text-[13px] text-ink-3">{detail}</span>
            </span>
          </div>
        ))}
      </div>

      <div className="mt-7">
        <PrimaryButton label="I have the right to download" onClick={accept} />
      </div>
      <p className="mt-3.5 text-[13px] text-ink-3">Shown once.</p>
    </div>
  )
}
