import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getNext, submitAnswer, type GradeResult, type RevisionItem } from '../api'
import DesignProblem from '../components/DesignProblem'
import FeedbackCard from '../components/FeedbackCard'
import QuestionCard from '../components/QuestionCard'

export default function RevisionPage() {
  const [answered, setAnswered] = useState(0)
  const [seen, setSeen] = useState<number[]>([])
  const [item, setItem] = useState<RevisionItem | null>(null)
  const [answer, setAnswer] = useState('')
  const [result, setResult] = useState<GradeResult | null>(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (answeredSoFar: number, exclude: number[]) => {
    try {
      const next = await getNext(answeredSoFar, exclude)
      setItem(next)
      setAnswer('')
      setResult(null)
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }, [])

  useEffect(() => {
    void load(0, [])
  }, [load])

  async function check() {
    if (item?.type !== 'QUESTION') return
    setChecking(true)
    try {
      setResult(await submitAnswer(item.question.id, answer))
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setChecking(false)
    }
  }

  function next(pointId?: number) {
    const nextAnswered = answered + 1
    const nextSeen = pointId === undefined ? seen : [...seen, pointId]
    setAnswered(nextAnswered)
    setSeen(nextSeen)
    void load(nextAnswered, nextSeen)
  }

  return (
    <main className={item?.type === 'PROBLEM' ? 'page page-wide' : 'page'}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h1 style={{ margin: 0, fontSize: '1.3rem' }}>Revision</h1>
        <div className="row">
          {answered > 0 && <span className="muted small">{answered} answered</span>}
          <Link className="button-secondary" to="/">
            Stop
          </Link>
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      {item === null && !error && <p className="muted">Loading…</p>}

      {item?.type === 'QUESTION' && (
        <>
          <QuestionCard
            question={item.question}
            answer={answer}
            onAnswerChange={setAnswer}
            onSubmit={check}
            checking={checking}
            locked={result !== null}
          />
          {result && <FeedbackCard result={result} questionId={item.question.id} onNext={() => next(item.question.pointId)} />}
        </>
      )}

      {item?.type === 'PROBLEM' && (
        <DesignProblem key={item.problem.id} problemId={item.problem.id} onDone={() => next()} />
      )}

      {item?.type === 'DONE' && (
        <section className="card">
          <p style={{ margin: 0 }}>All caught up — nothing due right now.</p>
          <Link to="/">Back to Home</Link>
        </section>
      )}
    </main>
  )
}
