import { useEffect, useRef, useState } from 'react'
import { submitAnswer, submitLessonDesign, type GradeResult, type MiniDesign, type QuestionView } from '../api'
import DesignCanvas from './DesignCanvas'
import FeedbackCard from './FeedbackCard'
import QuestionCard from './QuestionCard'

type Props = {
  mapItemId: number
  questions: QuestionView[]
  miniDesign: MiniDesign | null
  meta: string
  onFinished: () => void
}

// The lesson's questions one at a time, graded like revision, then the mini design on a blank canvas.
export default function QuickCheck({ mapItemId, questions, miniDesign, meta, onFinished }: Props) {
  const total = questions.length + (miniDesign ? 1 : 0)
  const [index, setIndex] = useState(0)
  const [answer, setAnswer] = useState('')
  const [result, setResult] = useState<GradeResult | null>(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const finished = useRef(false)

  useEffect(() => {
    if (index >= total && !finished.current) {
      finished.current = true
      onFinished()
    }
  }, [index, total, onFinished])

  function next() {
    setIndex((i) => i + 1)
    setAnswer('')
    setResult(null)
    setError(null)
  }

  async function check(question: QuestionView) {
    setChecking(true)
    try {
      setResult(await submitAnswer(question.id, answer))
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setChecking(false)
    }
  }

  if (index >= total) return null

  const progress = (
    <div className="muted small">
      Quick check · {index + 1} of {total}
    </div>
  )

  if (index < questions.length) {
    const question = questions[index]
    return (
      <div className="quick-check">
        {progress}
        <QuestionCard
          question={question}
          answer={answer}
          onAnswerChange={setAnswer}
          onSubmit={() => check(question)}
          checking={checking}
          locked={result !== null}
        />
        {error && <p className="error">{error}</p>}
        {result && <FeedbackCard result={result} questionId={question.id} onNext={next} />}
      </div>
    )
  }

  return (
    <div className="quick-check">
      {progress}
      <DesignCanvas
        meta={`${meta} · mini design`}
        title={miniDesign!.task}
        requirements={miniDesign!.requirements}
        submit={(s) => submitLessonDesign(mapItemId, s)}
        onDone={next}
      />
    </div>
  )
}
