import { render, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import Diagram from './Diagram'

const render$ = vi.fn()
vi.mock('mermaid', () => ({ default: { initialize: vi.fn(), render: (...a: unknown[]) => render$(...a) } }))

describe('Diagram', () => {
  it('rendersSvgFromMermaid', async () => {
    render$.mockResolvedValue({ svg: '<svg data-testid="d"></svg>' })
    const { container } = render(<Diagram source={'flowchart LR\n A --> B'} />)
    await waitFor(() => expect(container.querySelector('svg')).not.toBeNull())
  })

  it('brokenDiagramRendersNothing', async () => {
    render$.mockRejectedValue(new Error('Parse error'))
    const { container } = render(<Diagram source="nonsense" />)
    await waitFor(() => expect(render$).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
