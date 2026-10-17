import type { FormEvent, KeyboardEvent } from 'react'
import type { QuestionView } from '../api'

type Props = {
  question: QuestionView
  answer: string
  onAnswerChange: (value: string) => void
  onSubmit: () => void
  checking: boolean
  locked: boolean
}

export default function QuestionCard({ question, answer, onAnswerChange, onSubmit, checking, locked }: Props) {
  const quick = question.type === 'QUICKFIRE'

  function submit(e?: FormEvent) {
    e?.preventDefault()
    if (!checking && !locked) onSubmit()
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submit()
  }

  return (
    <form className="card" onSubmit={submit}>
      <div className="question-meta">
        {question.bookTitle} · {question.chapterTitle}
        {quick && ' · quick-fire'}
      </div>
      <p className="question-prompt">{question.prompt}</p>
      {quick ? (
        <input
          className="answer-input"
          value={answer}
          onChange={(e) => onAnswerChange(e.target.value)}
          readOnly={locked}
          autoFocus
          aria-label="Your answer"
        />
      ) : (
        <textarea
          className="answer-box"
          value={answer}
          onChange={(e) => onAnswerChange(e.target.value)}
          onKeyDown={onKeyDown}
          readOnly={locked}
          autoFocus
          aria-label="Your answer"
          placeholder="Answer as you would in an interview…"
        />
      )}
      {!locked && (
        <div className="row">
          <button type="submit" className="button-primary" disabled={checking}>
            {checking ? 'Checking…' : 'Check'}
          </button>
          <span className="muted small">{quick ? 'Enter to check' : 'Ctrl+Enter to check'}</span>
        </div>
      )}
    </form>
  )
}
