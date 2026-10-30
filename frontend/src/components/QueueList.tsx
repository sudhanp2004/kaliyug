import { Link } from 'react-router-dom'
import { pagesLabel, type QueueItem } from '../api'

type Props = {
  items: QueueItem[]
  busyId: number | null
  onSkip: (id: number) => void
  onRemove: (id: number) => void
}

export default function QueueList({ items, busyId, onSkip, onRemove }: Props) {
  return (
    <ol className="queue">
      {items.map((item, index) => {
        const current = index === 0
        return (
          <li key={item.id} className={current ? 'queue-item queue-current' : 'queue-item'}>
            <div className="queue-main">
              <div className="queue-title">
                <Link to={`/lesson/${item.id}`}>{item.title}</Link>
              </div>
              <div className="queue-meta">
                {item.bookTitle} · {item.chapterTitle}
              </div>
              <div className="queue-pages">{pagesLabel(item.startPage, item.endPage)}</div>
            </div>
            <div className="queue-actions">
              {current && (
                <>
                  <Link className="button-primary" to={`/lesson/${item.id}`}>
                    Start lesson
                  </Link>
                  <button className="button-secondary" disabled={busyId !== null} onClick={() => onSkip(item.id)}>
                    Skip for now
                  </button>
                </>
              )}
              <button
                className="link-button"
                disabled={busyId !== null}
                onClick={() => onRemove(item.id)}
                title="Take this section out of your syllabus (you can restore it in Library)"
              >
                Remove
              </button>
            </div>
          </li>
        )
      })}
    </ol>
  )
}
