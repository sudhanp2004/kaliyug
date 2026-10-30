import {
  addEdge,
  Controls,
  ReactFlow,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type Node,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useRef, useState, type FormEvent } from 'react'
import { overrideAttempt, pagesLabel, type DesignResult, type DesignSubmission } from '../api'

const PALETTE = ['Client', 'Load balancer', 'Web server', 'Cache', 'Database', 'Queue', 'CDN', 'ID generator']

type BoxData = { label: string }
type Props = {
  meta: string
  title: string
  requirements: string[]
  submit: (s: DesignSubmission) => Promise<DesignResult>
  onDone: () => void
}

// The drawing canvas and its review. It always starts blank.
export default function DesignCanvas({ meta, title, requirements, submit: send, onDone }: Props) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node<BoxData>>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const [notes, setNotes] = useState('')
  const [customLabel, setCustomLabel] = useState<string | null>(null)
  const [editingEdge, setEditingEdge] = useState<{ id: string; label: string } | null>(null)
  const [result, setResult] = useState<DesignResult | null>(null)
  const [overridden, setOverridden] = useState<number[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const nextId = useRef(1)

  function addBox(label: string) {
    const n = nextId.current++
    const slot = n - 1
    setNodes((current) => [
      ...current,
      { id: `n${n}`, position: { x: 40 + (slot % 4) * 170, y: 40 + Math.floor(slot / 4) * 100 }, data: { label } },
    ])
  }

  function addCustom(e: FormEvent) {
    e.preventDefault()
    const label = customLabel?.trim()
    if (label) addBox(label)
    setCustomLabel(null)
  }

  function saveEdgeLabel(e: FormEvent) {
    e.preventDefault()
    if (!editingEdge) return
    setEdges((current) => current.map((ed) => (ed.id === editingEdge.id ? { ...ed, label: editingEdge.label.trim() } : ed)))
    setEditingEdge(null)
  }

  async function submit() {
    setSubmitting(true)
    try {
      const res = await send({
        nodes: nodes.map((n) => ({ id: n.id, label: n.data.label })),
        edges: edges.map((ed) => ({ from: ed.source, to: ed.target, label: typeof ed.label === 'string' ? ed.label : '' })),
        notes,
      })
      setResult(res)
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  async function override(attemptId: number) {
    try {
      await overrideAttempt(attemptId)
      setOverridden((ids) => [...ids, attemptId])
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <section className="card">
      <div className="question-meta">{meta}</div>
      <h2 style={{ margin: 0 }}>{title}</h2>
      <ul className="requirements">
        {requirements.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>

      <div className="design-layout">
        <div className="palette">
          <div className="muted small">Click to add</div>
          {PALETTE.map((label) => (
            <button key={label} type="button" onClick={() => addBox(label)} disabled={result !== null}>
              {label}
            </button>
          ))}
          {customLabel === null ? (
            <button type="button" onClick={() => setCustomLabel('')} disabled={result !== null}>
              + Custom box
            </button>
          ) : (
            <form onSubmit={addCustom}>
              <input
                className="answer-input"
                aria-label="Box label"
                placeholder="Label, then Enter"
                value={customLabel}
                autoFocus
                onChange={(e) => setCustomLabel(e.target.value)}
                onBlur={() => setCustomLabel(null)}
              />
            </form>
          )}
          <div className="muted small" style={{ marginTop: '0.5rem' }}>
            Drag between box edges to connect. Double-click a connection to label it. Select and press Backspace to delete.
          </div>
        </div>

        <div className="canvas">
          {editingEdge && (
            <form onSubmit={saveEdgeLabel} className="row" style={{ padding: '0.5rem' }}>
              <input
                className="answer-input"
                aria-label="Connection label"
                value={editingEdge.label}
                autoFocus
                onChange={(e) => setEditingEdge({ ...editingEdge, label: e.target.value })}
                style={{ flex: 1 }}
              />
              <button className="button-secondary" type="submit">
                Save
              </button>
            </form>
          )}
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={(c: Connection) => setEdges((current) => addEdge(c, current))}
            onEdgeDoubleClick={(_, edge) =>
              setEditingEdge({ id: edge.id, label: typeof edge.label === 'string' ? edge.label : '' })
            }
            nodesConnectable={result === null}
            nodesDraggable={result === null}
            proOptions={{ hideAttribution: true }}
            style={{ height: 460 }}
          >
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>

        <div>
          <label className="muted small" htmlFor="design-notes">
            Notes
          </label>
          <textarea
            id="design-notes"
            className="notes-box"
            value={notes}
            readOnly={result !== null}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Your reasoning: trade-offs, estimates, why each choice…"
          />
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      {result === null ? (
        <div className="row">
          <button className="button-primary" onClick={submit} disabled={submitting}>
            {submitting ? 'Reviewing…' : 'Submit for review'}
          </button>
        </div>
      ) : (
        <div className="feedback">
          <p style={{ margin: 0 }}>{result.feedback}</p>
          {result.differences && (
            <div className="book-answer">
              <div className="muted small">Different from the book</div>
              <div>{result.differences}</div>
            </div>
          )}
          <ul className="point-results">
            {result.points.map((p) => (
              <li key={p.pointId}>
                <span className={`verdict-${p.verdict}`}>{p.verdict === 'CORRECT' ? '✓' : '✗'}</span>
                <span>{p.text}</span>
                <span className="muted small">{pagesLabel(p.firstPage, p.lastPage)}</span>
                {p.verdict !== 'CORRECT' &&
                  (overridden.includes(p.attemptId) ? (
                    <span className="verdict-CORRECT small">Counted as right</span>
                  ) : (
                    <button className="link-button" onClick={() => override(p.attemptId)}>
                      I was actually right
                    </button>
                  ))}
              </li>
            ))}
          </ul>
          <div className="row">
            <button className="button-primary" onClick={onDone}>
              Next
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
