import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import RevisionPage from './RevisionPage'

const question = {
  type: 'QUESTION',
  problem: null,
  question: {
    id: 11,
    type: 'SHORT',
    prompt: 'How does a token bucket work?',
    pointId: 5,
    bookTitle: 'System Design Interview',
    chapterTitle: 'CHAPTER 4: DESIGN A RATE LIMITER',
  },
}

const partial = {
  attemptId: 99,
  verdict: 'PARTIAL',
  feedback: 'You covered refilling but missed the bucket size.',
  notInBook: false,
  modelAnswer: 'Tokens refill at a fixed rate up to the bucket size.',
  firstPage: 52,
  lastPage: 52,
  bookTitle: 'System Design Interview',
}

function renderPage() {
  return render(
    <MemoryRouter>
      <RevisionPage />
    </MemoryRouter>,
  )
}

async function answer(text: string) {
  await userEvent.type(await screen.findByRole('textbox'), text)
  await userEvent.click(screen.getByRole('button', { name: 'Check' }))
}

afterEach(() => vi.unstubAllGlobals())

describe('RevisionPage', () => {
  it('answersAndShowsGroundedFeedback', async () => {
    const api = mockFetch([
      { path: '/api/revision/next', body: question },
      { method: 'POST', path: '/api/questions/11/answer', body: partial },
    ])
    renderPage()

    expect(await screen.findByText('How does a token bucket work?')).toBeInTheDocument()
    await answer('Tokens refill')

    expect(await screen.findByText('Partly right')).toBeInTheDocument()
    expect(screen.getByText('You covered refilling but missed the bucket size.')).toBeInTheDocument()
    expect(screen.getByText('Tokens refill at a fixed rate up to the bucket size.')).toBeInTheDocument()
    expect(screen.getByText('System Design Interview, p. 52')).toBeInTheDocument()
    expect(JSON.parse(api.calls.find((c) => c.method === 'POST')!.body!)).toEqual({ answer: 'Tokens refill' })
  })

  it('overrideCallsApi', async () => {
    const api = mockFetch([
      { path: '/api/revision/next', body: question },
      { method: 'POST', path: '/api/questions/11/answer', body: partial },
      { method: 'POST', path: '/api/attempts/99/override', status: 204 },
    ])
    renderPage()
    await answer('Tokens refill')

    await userEvent.click(await screen.findByRole('button', { name: 'I was actually right' }))

    expect(await screen.findByText('Counted as right')).toBeInTheDocument()
    expect(api.calls.some((c) => c.method === 'POST' && c.url === '/api/attempts/99/override')).toBe(true)
  })

  it('flagCallsApi', async () => {
    const api = mockFetch([
      { path: '/api/revision/next', body: question },
      { method: 'POST', path: '/api/questions/11/answer', body: partial },
      { method: 'POST', path: '/api/questions/11/flag', status: 204 },
    ])
    renderPage()
    await answer('Tokens refill')

    await userEvent.click(await screen.findByRole('button', { name: 'Flag question' }))

    expect(await screen.findByText("Won't ask this again")).toBeInTheDocument()
    expect(api.calls.some((c) => c.url === '/api/questions/11/flag')).toBe(true)
  })

  it('keepsAnswerWhenGradingFails', async () => {
    mockFetch([
      { path: '/api/revision/next', body: question },
      {
        method: 'POST',
        path: '/api/questions/11/answer',
        status: 503,
        body: { error: "AI is unreachable right now — your answer wasn't graded, try again." },
      },
    ])
    renderPage()
    await answer('my careful answer')

    expect(await screen.findByText("AI is unreachable right now — your answer wasn't graded, try again.")).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveValue('my careful answer')
    expect(screen.getByRole('button', { name: 'Check' })).toBeEnabled()
  })

  it('nextSendsAnsweredAndExclude', async () => {
    const api = mockFetch([
      { path: '/api/revision/next', body: question },
      { method: 'POST', path: '/api/questions/11/answer', body: partial },
    ])
    renderPage()
    await answer('Tokens refill')

    await userEvent.click(await screen.findByRole('button', { name: 'Next' }))

    const nextCalls = api.calls.filter((c) => c.url.startsWith('/api/revision/next'))
    expect(nextCalls).toHaveLength(2)
    expect(nextCalls[1].url).toContain('answered=1&exclude=5')
  })

  it('doneState', async () => {
    mockFetch([{ path: '/api/revision/next', body: { type: 'DONE', question: null, problem: null } }])
    renderPage()

    expect(await screen.findByText('All caught up — nothing due right now.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Home' })).toHaveAttribute('href', '/')
  })
})
