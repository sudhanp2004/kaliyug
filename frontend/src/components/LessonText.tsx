import type { ReactNode } from 'react'

// Lesson text from the AI: paragraphs, ``` code blocks and `inline code`. Rendered as React nodes, never as HTML.

function inline(text: string): ReactNode[] {
  return text.split(/(`[^`\n]+`)/g).map((piece, i) =>
    piece.length > 2 && piece.startsWith('`') && piece.endsWith('`') ? <code key={i}>{piece.slice(1, -1)}</code> : piece,
  )
}

export default function LessonText({ text }: { text: string }) {
  const segments = text.split('```')
  return (
    <>
      {segments.map((segment, i) => {
        if (i % 2 === 1) {
          // First line of a fence may name the language ("java").
          const code = segment.replace(/^[^\n]*\n/, (first) => (/^\s*\w*\s*\n$/.test(first) ? '' : first))
          return (
            <pre key={i} className="lesson-code">
              <code>{code.replace(/\n$/, '')}</code>
            </pre>
          )
        }
        return segment
          .split(/\n\s*\n/)
          .map((p) => p.trim())
          .filter((p) => p.length > 0)
          .map((p, j) => <p key={`${i}-${j}`}>{inline(p)}</p>)
      })}
    </>
  )
}
