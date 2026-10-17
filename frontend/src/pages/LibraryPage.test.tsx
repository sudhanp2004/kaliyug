import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import LibraryPage from './LibraryPage'

const ready = {
  id: 1,
  title: 'System Design Interview by Alex Xu',
  status: 'READY',
  failureReason: null,
  totalPages: 269,
  itemsReady: 40,
  itemsTotal: 40,
}

afterEach(() => vi.unstubAllGlobals())

describe('LibraryPage', () => {
  it('listsAvailableAndAdds', async () => {
    const api = mockFetch([
      { path: '/api/resources', body: [] },
      { path: '/api/resources/available', body: ['System Design Interview by Alex Xu.pdf'] },
      { path: '/api/map-items/hidden', body: [] },
      { method: 'POST', path: '/api/resources/import', status: 201, body: { ...ready, status: 'INGESTING', itemsReady: 0, itemsTotal: 0 } },
    ])
    render(<LibraryPage />)

    expect(await screen.findByText('System Design Interview by Alex Xu.pdf')).toBeInTheDocument()
    api.add({ path: '/api/resources', body: [{ ...ready, status: 'INGESTING', itemsReady: 3, itemsTotal: 12 }] })
    api.add({ path: '/api/resources/available', body: [] })

    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    const post = api.calls.find((c) => c.method === 'POST')!
    expect(JSON.parse(post.body!)).toEqual({ fileName: 'System Design Interview by Alex Xu.pdf' })
    expect(await screen.findByText('Reading… 3/12 sections')).toBeInTheDocument()
  })

  it('showsFailedReasonWithRetry', async () => {
    const api = mockFetch([
      { path: '/api/resources', body: [{ ...ready, status: 'FAILED', failureReason: 'AI service unreachable — press Retry in Library.' }] },
      { path: '/api/resources/available', body: [] },
      { path: '/api/map-items/hidden', body: [] },
      { method: 'POST', path: '/api/resources/1/retry', status: 204 },
    ])
    render(<LibraryPage />)

    expect(await screen.findByText('AI service unreachable — press Retry in Library.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(api.calls.some((c) => c.method === 'POST' && c.url === '/api/resources/1/retry')).toBe(true)
  })

  it('duplicateShows409Message', async () => {
    mockFetch([
      { path: '/api/resources', body: [ready] },
      { path: '/api/resources/available', body: ['Copy of Alex Xu.pdf'] },
      { path: '/api/map-items/hidden', body: [] },
      { method: 'POST', path: '/api/resources/import', status: 409, body: { error: 'This book is already in your library.' } },
    ])
    render(<LibraryPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Add' }))

    expect(await screen.findByText('This book is already in your library.')).toBeInTheDocument()
  })

  it('restoresHiddenItem', async () => {
    const api = mockFetch([
      { path: '/api/resources', body: [ready] },
      { path: '/api/resources/available', body: [] },
      {
        path: '/api/map-items/hidden',
        body: [{ id: 9, title: 'Mailing list page', bookTitle: 'System Design Interview', chapterTitle: 'FORWARD', startPage: 4, endPage: 4 }],
      },
      { method: 'POST', path: '/api/map-items/9/unhide', status: 204 },
    ])
    render(<LibraryPage />)

    expect(await screen.findByText('Mailing list page')).toBeInTheDocument()
    api.add({ path: '/api/map-items/hidden', body: [] })
    await userEvent.click(screen.getByRole('button', { name: 'Restore' }))

    expect(api.calls.some((c) => c.url === '/api/map-items/9/unhide')).toBe(true)
    expect(await screen.findByText('Nothing hidden.')).toBeInTheDocument()
  })

  it('readyBookShowsReady', async () => {
    mockFetch([
      { path: '/api/resources', body: [ready] },
      { path: '/api/resources/available', body: [] },
      { path: '/api/map-items/hidden', body: [] },
    ])
    render(<LibraryPage />)

    expect(await screen.findByText('Ready')).toBeInTheDocument()
  })
})
