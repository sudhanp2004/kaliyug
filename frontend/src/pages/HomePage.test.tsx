import { render, screen, waitFor } from '@testing-library/react'
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
      mastered: false,
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
    expect(screen.getByRole('link', { name: 'Start lesson' })).toHaveAttribute('href', '/lesson/7')
    expect(screen.queryByRole('button', { name: 'Learned it' })).not.toBeInTheDocument()
  })

  it('preparesUpcomingLessonsAndShowsMastered', async () => {
    const api = mockFetch([
      { path: '/api/progress', body: { ...progress, books: [{ ...progress.books[0], mastered: true }] } },
      { path: '/api/queue', body: { items: [item(7, 'Base62 conversion', 119, 124)], remaining: 1, preparing: false, skipped: 0, allPaused: false } },
      { method: 'POST', path: '/api/lessons/prepare-upcoming', status: 204 },
    ])
    renderHome()
    expect(await screen.findByText('Mastered: System Design Interview')).toBeInTheDocument()
    await waitFor(() => expect(api.calls.some((c) => c.url === '/api/lessons/prepare-upcoming')).toBe(true))
    expect(screen.getByRole('link', { name: 'Base62 conversion' })).toHaveAttribute('href', '/lesson/7')
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

  it('skipMovesItemAndShowsSkippedNote', async () => {
    const api = mockFetch([
      { path: '/api/progress', body: progress },
      { path: '/api/queue', body: { items: [item(7, 'Base62 conversion', 119, 124), item(8, 'Hash collisions', 125, 131)], remaining: 2, preparing: false, skipped: 0, allPaused: false } },
      { method: 'POST', path: '/api/map-items/7/skip', status: 204 },
    ])
    renderHome()
    const skip = await screen.findByRole('button', { name: 'Skip for now' })
    expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Hide' })).not.toBeInTheDocument()
    api.add({ path: '/api/queue', body: { items: [item(8, 'Hash collisions', 125, 131)], remaining: 1, preparing: false, skipped: 1, allPaused: false } })

    await userEvent.click(skip)

    expect(api.calls.some((c) => c.method === 'POST' && c.url === '/api/map-items/7/skip')).toBe(true)
    expect(await screen.findByText(/1 skipped section — it comes back after you learn 10 more/)).toBeInTheDocument()
  })

  it('allPausedPointsToLibrary', async () => {
    mockFetch([
      { path: '/api/progress', body: progress },
      { path: '/api/queue', body: { items: [], remaining: 0, preparing: false, skipped: 0, allPaused: true } },
    ])

    renderHome()

    expect(await screen.findByText(/Learning is paused for all your books/)).toBeInTheDocument()
    expect(screen.queryByText(/covered everything/)).not.toBeInTheDocument()
  })
})
