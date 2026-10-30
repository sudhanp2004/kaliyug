import { useCallback, useEffect, useState, type ChangeEvent } from 'react'
import {
  getAvailable,
  getHidden,
  getProgress,
  getResources,
  importBook,
  pagesLabel,
  retryResource,
  setLearning,
  setRevision,
  unhideItem,
  uploadBook,
  type QueueItem,
  type ResourceInfo,
} from '../api'

const POLL_MS = 5000

function statusText(r: ResourceInfo) {
  if (r.status === 'READY') return 'Ready'
  if (r.status === 'INGESTING') return r.itemsTotal > 0 ? `Reading… ${r.itemsReady}/${r.itemsTotal} sections` : 'Reading…'
  return 'Failed'
}

export default function LibraryPage() {
  const [books, setBooks] = useState<ResourceInfo[] | null>(null)
  const [available, setAvailable] = useState<string[]>([])
  const [hidden, setHidden] = useState<QueueItem[]>([])
  const [mastered, setMastered] = useState<number[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      // Progress only adds the Mastered pill; the Library still works without it.
      const [b, a, h, p] = await Promise.all([getResources(), getAvailable(), getHidden(), getProgress().catch(() => null)])
      setBooks(b)
      setAvailable(a)
      setHidden(h)
      if (p) setMastered(p.books.filter((bb) => bb.mastered).map((bb) => bb.resourceId))
    } catch (e) {
      setError((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const reading = books?.some((b) => b.status === 'INGESTING') ?? false
  useEffect(() => {
    if (!reading) return
    const timer = setInterval(() => void refresh(), POLL_MS)
    return () => clearInterval(timer)
  }, [reading, refresh])

  async function act(action: () => Promise<unknown>) {
    setBusy(true)
    try {
      await action()
      setError(null)
      await refresh()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  function onUpload(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (file) void act(() => uploadBook(file))
  }

  return (
    <main className="page">
      <h1 style={{ margin: 0, fontSize: '1.3rem' }}>Library</h1>
      {error && <p className="error">{error}</p>}

      <section className="card">
        <h2 style={{ margin: 0 }}>Your books</h2>
        {books === null ? (
          <p className="muted">Loading…</p>
        ) : books.length === 0 ? (
          <p className="muted">No books yet. Add one below.</p>
        ) : (
          <div>
            {books.map((b) => (
              <div key={b.id} className="book-row">
                <div>
                  <div style={{ fontWeight: 600 }}>
                    {b.title}
                    {mastered.includes(b.id) && <span className="pill">Mastered</span>}
                  </div>
                  {b.status === 'FAILED' && <div className="small status-FAILED">{b.failureReason}</div>}
                </div>
                <div className="row">
                  <label className="row small" title="Off: this book is left out of Up next">
                    <input
                      type="checkbox"
                      checked={b.learningOn}
                      disabled={busy}
                      onChange={(e) => act(() => setLearning(b.id, e.target.checked))}
                    />
                    Learning
                  </label>
                  <label className="row small" title="Off: no revision questions from this book">
                    <input
                      type="checkbox"
                      checked={b.revisionOn}
                      disabled={busy}
                      onChange={(e) => act(() => setRevision(b.id, e.target.checked))}
                    />
                    Revision
                  </label>
                  <span className={`small status-${b.status}`}>{statusText(b)}</span>
                  {b.status === 'FAILED' && (
                    <button className="button-secondary" disabled={busy} onClick={() => act(() => retryResource(b.id))}>
                      Retry
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="card">
        <h2 style={{ margin: 0 }}>In your project folder</h2>
        {available.length === 0 ? (
          <p className="muted small" style={{ margin: 0 }}>
            No new PDFs in the project folder.
          </p>
        ) : (
          <div>
            {available.map((name) => (
              <div key={name} className="book-row">
                <span>{name}</span>
                <button className="button-primary" disabled={busy} onClick={() => act(() => importBook(name))}>
                  Add
                </button>
              </div>
            ))}
          </div>
        )}
        <label className="row small">
          <span className="muted">Or upload a PDF:</span>
          <input type="file" accept="application/pdf,.pdf" onChange={onUpload} disabled={busy} />
        </label>
      </section>

      <section className="card">
        <h2 style={{ margin: 0 }}>Removed sections</h2>
        {hidden.length === 0 ? (
          <p className="muted small" style={{ margin: 0 }}>
            Nothing removed.
          </p>
        ) : (
          <div>
            {hidden.map((item) => (
              <div key={item.id} className="book-row">
                <div>
                  <div>{item.title}</div>
                  <div className="muted small">
                    {item.bookTitle} · {item.chapterTitle} · {pagesLabel(item.startPage, item.endPage)}
                  </div>
                </div>
                <button className="button-secondary" disabled={busy} onClick={() => act(() => unhideItem(item.id))}>
                  Restore
                </button>
              </div>
            ))}
          </div>
        )}
      </section>
    </main>
  )
}
