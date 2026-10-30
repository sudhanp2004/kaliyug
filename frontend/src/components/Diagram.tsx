import { useEffect, useState } from 'react'

// Draws a Mermaid diagram. Mermaid is loaded only when a lesson has a diagram.
// A diagram that fails to parse is left out rather than shown broken.

let initialized = false
let counter = 0

export default function Diagram({ source }: { source: string }) {
  const [svg, setSvg] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setSvg(null)
    ;(async () => {
      try {
        const { default: mermaid } = await import('mermaid')
        if (!initialized) {
          mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true })
          initialized = true
        }
        counter += 1
        const { svg } = await mermaid.render(`lesson-diagram-${counter}`, source)
        if (!cancelled) setSvg(svg)
      } catch {
        if (!cancelled) setSvg(null)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [source])

  if (!svg) return null
  // Mermaid's own output; strict security level sanitizes labels.
  return <div className="diagram" dangerouslySetInnerHTML={{ __html: svg }} />
}
