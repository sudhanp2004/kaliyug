import { useState } from 'react'
import { flagQuestion, overrideAttempt, pagesLabel, type GradeResult, type Verdict } from '../api'

const VERDICT_LABEL: Record<Verdict, string> = {
  CORRECT: 'Right',
  PARTIAL: 'Partly right',
  WRONG: 'Not quite',
}

type Props = { result: GradeResult; questionId: number; onNext: () => void }

export default function FeedbackCard({ result, questionId, onNext }: Props) {
  const [overridden, setOverridden] = useState(false)
  const [flagged, setFlagged] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<void>, done: () => void) {
    try {
      await action()
      done()
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <section className={`card feedback feedback-${result.verdict}`}>
      <div className={`verdict verdict-${result.verdict}`}>{VERDICT_LABEL[result.verdict]}</div>
      <p style={{ margin: 0 }}>{result.feedback}</p>
      {result.notInBook && <p className="note">The book doesn't settle this one.</p>}
      <div className="book-answer">
        <div className="muted small">Book's answer</div>
        <div>{result.modelAnswer}</div>
        <div className="muted small">
          {result.bookTitle}, {pagesLabel(result.firstPage, result.lastPage)}
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      <div className="row">
        <button className="button-primary" onClick={onNext} autoFocus>
          Next
        </button>
        {result.verdict !== 'CORRECT' &&
          (overridden ? (
            <span className="verdict-CORRECT small">Counted as right</span>
          ) : (
            <button className="button-secondary" onClick={() => run(() => overrideAttempt(result.attemptId), () => setOverridden(true))}>
              I was actually right
            </button>
          ))}
        {flagged ? (
          <span className="muted small">Won't ask this again</span>
        ) : (
          <button className="link-button" onClick={() => run(() => flagQuestion(questionId), () => setFlagged(true))}>
            Flag question
          </button>
        )}
      </div>
    </section>
  )
}
