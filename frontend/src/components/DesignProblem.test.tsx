import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import DesignProblem from './DesignProblem'

const problem = {
  id: 3,
  title: 'Design a URL shortener',
  requirements: ['100M URLs per day', 'Read:write = 10:1'],
  chapterTitle: 'CHAPTER 8: DESIGN A URL SHORTENER',
  bookTitle: 'System Design Interview',
}

const result = {
  feedback: 'Good base62 choice; reread p. 128 for the length estimate.',
  differences: '',
  points: [
    { pointId: 1, text: 'Base62 conversion', firstPage: 125, lastPage: 125, verdict: 'CORRECT', attemptId: 41 },
    { pointId: 2, text: 'URL length estimate', firstPage: 128, lastPage: 128, verdict: 'WRONG', attemptId: 42 },
  ],
}

afterEach(() => vi.unstubAllGlobals())

describe('DesignProblem', () => {
  it('paletteClickAddsNodeAndSubmitSendsStructure', async () => {
    const api = mockFetch([
      { path: '/api/problems/3', body: problem },
      { method: 'POST', path: '/api/problems/3/submit', body: result },
    ])
    render(<DesignProblem problemId={3} onDone={() => {}} />)

    expect(await screen.findByText('100M URLs per day')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Load balancer' }))
    await userEvent.click(screen.getByRole('button', { name: 'Database' }))
    await userEvent.type(screen.getByLabelText('Notes'), '302 so we can track clicks')
    await userEvent.click(screen.getByRole('button', { name: 'Submit for review' }))

    const body = JSON.parse(api.calls.find((c) => c.method === 'POST')!.body!)
    expect(body.nodes.map((n: { label: string }) => n.label)).toEqual(['Load balancer', 'Database'])
    expect(body.edges).toEqual([])
    expect(body.notes).toBe('302 so we can track clicks')
  })

  it('customBoxUsesInlineLabel', async () => {
    const api = mockFetch([
      { path: '/api/problems/3', body: problem },
      { method: 'POST', path: '/api/problems/3/submit', body: result },
    ])
    render(<DesignProblem problemId={3} onDone={() => {}} />)

    await userEvent.click(await screen.findByRole('button', { name: '+ Custom box' }))
    await userEvent.type(screen.getByLabelText('Box label'), 'Bloom filter{Enter}')
    await userEvent.click(screen.getByRole('button', { name: 'Submit for review' }))

    const body = JSON.parse(api.calls.find((c) => c.method === 'POST')!.body!)
    expect(body.nodes.map((n: { label: string }) => n.label)).toEqual(['Bloom filter'])
  })

  it('showsCoveredAndMissedPoints', async () => {
    const api = mockFetch([
      { path: '/api/problems/3', body: problem },
      { method: 'POST', path: '/api/problems/3/submit', body: result },
      { method: 'POST', path: '/api/attempts/42/override', status: 204 },
    ])
    const onDone = vi.fn()
    render(<DesignProblem problemId={3} onDone={onDone} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Cache' }))
    await userEvent.click(screen.getByRole('button', { name: 'Submit for review' }))

    expect(await screen.findByText('Good base62 choice; reread p. 128 for the length estimate.')).toBeInTheDocument()
    expect(screen.getByText('Base62 conversion')).toBeInTheDocument()
    expect(screen.getByText('URL length estimate')).toBeInTheDocument()
    expect(screen.queryByText('Different from the book')).not.toBeInTheDocument()
    const overrides = screen.getAllByRole('button', { name: 'I was actually right' })
    expect(overrides).toHaveLength(1)

    await userEvent.click(overrides[0])
    expect(await screen.findByText('Counted as right')).toBeInTheDocument()
    expect(api.calls.some((c) => c.url === '/api/attempts/42/override')).toBe(true)

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(onDone).toHaveBeenCalled()
  })

  it('emptyCanvasErrorShown', async () => {
    mockFetch([
      { path: '/api/problems/3', body: problem },
      { method: 'POST', path: '/api/problems/3/submit', status: 400, body: { error: 'Add at least one box before submitting.' } },
    ])
    render(<DesignProblem problemId={3} onDone={() => {}} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Submit for review' }))

    expect(await screen.findByText('Add at least one box before submitting.')).toBeInTheDocument()
  })
})
