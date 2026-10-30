import { Fragment, useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  ApiError,
  getLesson,
  getQueue,
  hideItem,
  markLearned,
  openLesson,
  pagesLabel,
  rewriteLesson,
  skipItem,
  type Lesson,
  type LessonPart,
} from '../api'
import Diagram from '../components/Diagram'
import LessonText from '../components/LessonText'
import QuickCheck from '../components/QuickCheck'

type Phase = 'reading' | 'checking' | 'done'

function Pages({ part }: { part: LessonPart }) {
  return <span className="muted small">({pagesLabel(part.firstPage, part.lastPage)})</span>
}

export default function LessonPage({ pollMs = 2000 }: { pollMs?: number }) {
  const id = Number(useParams().id)
  const navigate = useNavigate()
  const [lesson, setLesson] = useState<Lesson | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>('reading')
  const [doneMessage, setDoneMessage] = useState('')
  const [pollTick, setPollTick] = useState(0)

  const fail = useCallback((e: unknown) => {
    if (e instanceof ApiError && e.status === 404) setNotFound(true)
    else setError((e as Error).message)
  }, [])

  useEffect(() => {
    setLesson(null)
    setNotFound(false)
    setError(null)
    setPhase('reading')
    openLesson(id).then(setLesson).catch(fail)
  }, [id, fail])

  // While the lesson is being written, check back every pollMs.
  useEffect(() => {
    if (lesson?.status !== 'PREPARING') return
    const timer = setTimeout(() => {
      getLesson(id)
        .then((l) => {
          setLesson(l)
          setError(null)
        })
        .catch((e) => {
          fail(e)
          setPollTick((t) => t + 1)
        })
    }, pollMs)
    return () => clearTimeout(timer)
  }, [id, lesson, pollMs, pollTick, fail])

  async function act(action: () => Promise<void>) {
    try {
      await action()
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const learnedNow = () =>
    act(async () => {
      await markLearned(id)
      setLesson((l) => (l ? { ...l, learned: true } : l))
      setDoneMessage('Section learned — its points are now in your revision.')
      setPhase('done')
    })

  const finishCheck = useCallback(() => {
    if (lesson && !lesson.learned) {
      void learnedNow()
    } else {
      setDoneMessage('Practice done.')
      setPhase('done')
    }
    // learnedNow only reads id and setters
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lesson])

  const rewrite = () =>
    act(async () => {
      setPhase('reading')
      setLesson(await rewriteLesson(id))
    })

  const nextLesson = () =>
    act(async () => {
      const queue = await getQueue(1)
      navigate(queue.items.length > 0 ? `/lesson/${queue.items[0].id}` : '/')
    })

  const skip = () => act(async () => {
    await skipItem(id)
    navigate('/')
  })

  const remove = () => act(async () => {
    await hideItem(id)
    navigate('/')
  })

  if (notFound) {
    return (
      <main className="page">
        <section className="card">
          <p>This section isn't in your syllabus.</p>
          <Link to="/">Home</Link>
        </section>
      </main>
    )
  }

  if (!lesson) {
    return (
      <main className="page">
        <section className="card">{error ? <p className="error">{error}</p> : <p className="muted">Opening lesson…</p>}</section>
      </main>
    )
  }

  const pages = pagesLabel(lesson.startPage, lesson.endPage)
  const sideActions = (
    <>
      <button className="link-button" onClick={skip}>
        Skip for now
      </button>
      <button className="link-button" onClick={remove}>
        Remove
      </button>
    </>
  )

  if (lesson.status === 'PREPARING') {
    return (
      <main className="page">
        <section className="card">
          <div className="question-meta">
            {lesson.bookTitle} · {lesson.chapterTitle} · {pages}
          </div>
          <h2 style={{ margin: 0 }}>{lesson.sectionTitle}</h2>
          <p className="muted">Preparing your lesson…</p>
          {error && <p className="error">{error}</p>}
        </section>
      </main>
    )
  }

  if (lesson.status === 'FAILED') {
    return (
      <main className="page">
        <section className="card">
          <div className="question-meta">
            {lesson.bookTitle} · {lesson.chapterTitle} · {pages}
          </div>
          <h2 style={{ margin: 0 }}>{lesson.sectionTitle}</h2>
          <p className="error">{lesson.failureReason}</p>
          <p>
            Read {pages} in {lesson.bookTitle} instead.
          </p>
          {error && <p className="error">{error}</p>}
          <div className="row">
            <button className="button-primary" onClick={rewrite}>
              Try again
            </button>
            {sideActions}
          </div>
        </section>
      </main>
    )
  }

  const checkCount = lesson.quickCheck.length + (lesson.miniDesign ? 1 : 0)
  const meta = `${lesson.bookTitle} · ${lesson.chapterTitle}`
  const multi = lesson.blocks.length > 1

  return (
    <main className="page">
      <article className="card lesson">
        <div className="question-meta">
          {lesson.bookTitle} · {lesson.chapterTitle} · {pages} · about {lesson.estimatedMinutes} min
          {lesson.learned && <span className="pill">Learned</span>}
        </div>
        <h2 style={{ margin: 0 }}>{lesson.sectionTitle}</h2>

        {lesson.newPointsFound > 0 && (
          <p className="notice">
            {lesson.newPointsFound} new point{lesson.newPointsFound === 1 ? '' : 's'} found in this section, now in your revision.
          </p>
        )}

        {lesson.buildsOn.length > 0 && (
          <div className="builds-on">
            <strong>Builds on what you've learned</strong>
            <ul>
              {lesson.buildsOn.map((link) => (
                <li key={link.mapItemId}>
                  <Link to={`/lesson/${link.mapItemId}`}>{link.title}</Link>
                  <span className="muted small"> · {link.bookTitle}</span> — {link.why}
                </li>
              ))}
            </ul>
          </div>
        )}

        {lesson.blocks.map((block, i) => (
          <Fragment key={i}>
            {multi && (
              <h3 className="lesson-part">
                Part {i + 1} · {pagesLabel(block.idea.firstPage, Math.max(block.idea.lastPage, block.howItWorks.lastPage))}
              </h3>
            )}
            <h3>The idea</h3>
            <LessonText text={block.idea.text} />
            <Pages part={block.idea} />
            <h3>How it works</h3>
            <LessonText text={block.howItWorks.text} />
            <Pages part={block.howItWorks} />
            {i === 0 && lesson.diagrams.map((d, k) => <Diagram key={k} source={d} />)}
            {block.remember.length > 0 && (
              <>
                <h3>What to remember</h3>
                <ul className="remember">
                  {block.remember.map((r, k) => (
                    <li key={k}>
                      <LessonText text={r.text} /> <Pages part={r} />
                    </li>
                  ))}
                </ul>
              </>
            )}
          </Fragment>
        ))}

        {lesson.leftOut.trim() && <p className="muted small">{lesson.leftOut}</p>}

        <div className="row lesson-links">
          {lesson.youtubeUrl && (
            <a href={lesson.youtubeUrl} target="_blank" rel="noreferrer">
              ▶ Watch on YouTube
            </a>
          )}
          <span>
            📖 Read the full version: {lesson.bookTitle}, {pages}
          </span>
        </div>
      </article>

      <section className="card">
        <h3 style={{ marginTop: 0 }}>Quick check</h3>
        {phase === 'reading' &&
          (checkCount === 0 ? (
            <p className="muted">No quick-check questions for this section.</p>
          ) : (
            <div className="row">
              <button className="button-primary" onClick={() => setPhase('checking')}>
                {lesson.learned ? 'Practice (optional)' : `Start quick check (${checkCount})`}
              </button>
              {!lesson.learned && <span className="muted small">Finishing it marks the section learned.</span>}
            </div>
          ))}
        {phase === 'checking' && (
          <QuickCheck
            mapItemId={id}
            questions={lesson.quickCheck}
            miniDesign={lesson.miniDesign}
            meta={meta}
            onFinished={finishCheck}
          />
        )}
        {phase === 'done' && (
          <>
            <p className="notice">{doneMessage}</p>
            <div className="row">
              <button className="button-primary" onClick={nextLesson}>
                Next lesson
              </button>
              <Link to="/">Home</Link>
            </div>
          </>
        )}
        {error && <p className="error">{error}</p>}
      </section>

      <div className="row lesson-footer">
        {!lesson.learned && phase !== 'done' && (
          <button className="link-button" onClick={learnedNow}>
            Mark learned without the check
          </button>
        )}
        {sideActions}
        <button className="link-button" onClick={rewrite}>
          Rewrite this lesson
        </button>
      </div>
    </main>
  )
}
