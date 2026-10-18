import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getProgress, getQueue, hideItem, markLearned, skipItem, type Progress, type Queue } from '../api'
import ProgressBar from '../components/ProgressBar'
import QueueList from '../components/QueueList'

export default function HomePage() {
  const [progress, setProgress] = useState<Progress | null>(null)
  const [queue, setQueue] = useState<Queue | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      const [p, q] = await Promise.all([getProgress(), getQueue()])
      setProgress(p)
      setQueue(q)
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  async function act(id: number, action: (id: number) => Promise<void>) {
    setBusyId(id)
    try {
      await action(id)
      await refresh()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusyId(null)
    }
  }

  if (!progress || !queue) {
    return <main className="page">{error ? <p className="error">{error}</p> : <p className="muted">Loading…</p>}</main>
  }

  const { wins } = progress
  const noBooks = progress.books.length === 0

  return (
    <main className="page">
      {error && <p className="error">{error}</p>}

      <section className="card">
        <ProgressBar label="Overall" bar={progress.overall} large />
        {progress.books.length > 1 &&
          progress.books.map((b) => <ProgressBar key={b.resourceId} label={b.title} bar={b.bar} />)}
        {progress.books
          .filter((b) => b.status === 'FAILED')
          .map((b) => (
            <p key={b.resourceId} className="error">
              <strong>{b.title}:</strong> {b.failureReason}
            </p>
          ))}
        {(wins.weakPointsFixedThisWeek > 0 || wins.solidChapters.length > 0) && (
          <div className="wins">
            {wins.weakPointsFixedThisWeek > 0 && <div>{wins.weakPointsFixedThisWeek} weak points fixed this week</div>}
            {wins.solidChapters.length > 0 && <div>Solid: {wins.solidChapters.join(', ')}</div>}
          </div>
        )}
        <Link className="button-primary button-large" to="/revise">
          Revise ({progress.dueCount} due)
        </Link>
      </section>

      <section>
        <h2>Up next</h2>
        {noBooks ? (
          <p className="muted">
            <Link to="/library">Add a book in Library to get started.</Link>
          </p>
        ) : (
          <>
            {queue.items.length > 0 && (
              <QueueList
                items={queue.items}
                busyId={busyId}
                onLearned={(id) => act(id, markLearned)}
                onSkip={(id) => act(id, skipItem)}
                onRemove={(id) => act(id, hideItem)}
              />
            )}
            {queue.remaining > queue.items.length && (
              <p className="muted">… {queue.remaining - queue.items.length} more after these</p>
            )}
            {queue.skipped > 0 && (
              <p className="muted">
                {queue.skipped} skipped {queue.skipped === 1 ? 'section — it comes' : 'sections — they come'} back after you learn 10 more.
              </p>
            )}
            {queue.allPaused && (
              <p className="muted">
                Learning is paused for all your books. <Link to="/library">Switch one on in Library.</Link>
              </p>
            )}
            {(queue.preparing || progress.preparing) && (
              <p className="muted">Still reading your book… more items will appear.</p>
            )}
            {queue.items.length === 0 && !queue.allPaused && !queue.preparing && !progress.preparing && (
              <p className="muted">You've covered everything in your books. Keep revising.</p>
            )}
          </>
        )}
      </section>
    </main>
  )
}
