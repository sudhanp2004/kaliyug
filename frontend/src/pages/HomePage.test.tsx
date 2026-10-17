import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import HomePage from './HomePage'

const progress = {
  overall: { knownWell: 2, total: 4, percent: 50 },
  books: [
    {
      resourceId: 1,
      title: 'System Design Interview',
      status: 'READY',
      failureReason: null,
      bar: { knownWell: 2, total: 4, percent: 50 },
    },
  ],
  wins: { weakPointsFixedThisWeek: 6, solidChapters: ['CHAPTER 1: SCALE FROM ZERO TO MILLIONS OF USERS'] },
  dueCount: 3,
  preparing: false,
}

const item = (id: number, title: string, start: number, end: number) => ({
  id,
  title,
  bookTitle: 'System Design Interview',
  chapterTitle: 'CHAPTER 8: DESIGN A URL SHORTENER',
  startPage: start,
  endPage: end,
})

function renderHome() {
  return render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('HomePage', () => {
  it('showsProgressQueueAndDueCount', async () => {
    mockFetch([
      { path: '/api/progress', body: progress },
      { path: '/api/queue', body: { items: [item(7, 'Base62 conversion', 119, 124), item(8, 'Hash collisions', 125, 131)], remaining: 2, preparing: false } },
    ])

    renderHome()

    expect(await screen.findByText('50% known well · 2 of 4 points')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Revise (3 due)' })).toHaveAttribute('href', '/revise')
    expect(screen.getByText('6 weak points fixed this week')).toBeInTheDocument()
    expect(screen.getByText('pp. 119–124')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Learned it' })).toHaveLength(1)
  })

  it('learnedItRefetches', async () => {
    const api = mockFetch([
      { path: '/api/progress', body: progress },
      { path: '/api/queue', body: { items: [item(7, 'Base62 conversion', 119, 124), item(8, 'Hash collisions', 125, 131)], remaining: 2, preparing: false } },
      { method: 'POST', path: '/api/map-items/7/learned', status: 204 },
    ])
    renderHome()
    const button = await screen.findByRole('button', { name: 'Learned it' })
    api.add({ path: '/api/queue', body: { items: [item(8, 'Hash collisions', 125, 131)], remaining: 1, preparing: false } })

    await userEvent.click(button)

    expect(api.calls.some((c) => c.method === 'POST' && c.url === '/api/map-items/7/learned')).toBe(true)
    expect(await screen.findByText('pp. 125–131')).toBeInTheDocument()
    expect(screen.queryByText('Base62 conversion')).not.toBeInTheDocument()
  })

  it('emptyLibraryPromptsToAddBook', async () => {
    mockFetch([
      { path: '/api/progress', body: { ...progress, books: [], overall: { knownWell: 0, total: 0, percent: 0 }, dueCount: 0, wins: { weakPointsFixedThisWeek: 0, solidChapters: [] } } },
      { path: '/api/queue', body: { items: [], remaining: 0, preparing: false } },
    ])

    renderHome()

    expect(await screen.findByText(/Add a book in Library to get started/)).toBeInTheDocument()
  })

  it('showsFailedBookReasonAndPreparingNote', async () => {
    mockFetch([
      {
        path: '/api/progress',
        body: {
          ...progress,
          preparing: true,
          books: [{ ...progress.books[0], status: 'FAILED', failureReason: 'AI service unreachable — press Retry in Library.' }],
        },
      },
      { path: '/api/queue', body: { items: [item(7, 'Base62 conversion', 119, 124)], remaining: 1, preparing: true } },
    ])

    renderHome()

    expect(await screen.findByText('AI service unreachable — press Retry in Library.')).toBeInTheDocument()
    expect(screen.getByText('Still reading your book… more items will appear.')).toBeInTheDocument()
  })
})
