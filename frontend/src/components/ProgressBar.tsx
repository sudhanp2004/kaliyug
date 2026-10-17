import type { Bar } from '../api'

type Props = { label: string; bar: Bar; large?: boolean }

export default function ProgressBar({ label, bar, large = false }: Props) {
  return (
    <div className={large ? 'progress progress-large' : 'progress'}>
      <div className="progress-label">{label}</div>
      <div
        className="progress-track"
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={bar.percent}
      >
        <div className="progress-fill" style={{ width: `${bar.percent}%` }} />
      </div>
      <div className="progress-caption">
        {bar.percent}% known well · {bar.knownWell} of {bar.total} points
      </div>
    </div>
  )
}
