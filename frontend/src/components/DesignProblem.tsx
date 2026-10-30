import { useEffect, useState } from 'react'
import { getProblem, submitDesign, type Problem } from '../api'
import DesignCanvas from './DesignCanvas'

type Props = { problemId: number; onDone: () => void }

export default function DesignProblem({ problemId, onDone }: Props) {
  const [problem, setProblem] = useState<Problem | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getProblem(problemId)
      .then(setProblem)
      .catch((e: Error) => setError(e.message))
  }, [problemId])

  if (!problem) {
    return <section className="card">{error ? <p className="error">{error}</p> : <p className="muted">Loading problem…</p>}</section>
  }

  return (
    <DesignCanvas
      meta={`${problem.bookTitle} · ${problem.chapterTitle} · design problem`}
      title={problem.title}
      requirements={problem.requirements}
      submit={(s) => submitDesign(problemId, s)}
      onDone={onDone}
    />
  )
}
