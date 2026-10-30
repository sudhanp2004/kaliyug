import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GradeResult, Lesson, Verdict } from '../api'
import { mockFetch } from '../test/mockFetch'
import LessonPage from './LessonPage'

vi.mock('mermaid', () => ({ default: { initialize: vi.fn(), render: vi.fn().mockResolvedValue({ svg: '<svg></svg>' }) } }))

const ready: Lesson = {
  mapItemId: 7,
  status: 'READY',
  failureReason: null,
  sectionTitle: 'Cache',
  bookTitle: 'System Design Interview',
  chapterTitle: 'Chapter 1',
  startPage: 13,
  endPage: 15,
  learned: false,
  estimatedMinutes: 6,
  blocks: [
    {
      idea: { text: 'A cache keeps hot data in memory.', firstPage: 13, lastPage: 13 },
      howItWorks: { text: 'Check the cache first; on a miss read the database.', firstPage: 13, lastPage: 14 },
      remember: [
        { text: 'LRU is the most common eviction policy.', firstPage: 15, lastPage: 15, evidence: 'e' },
        { text: 'Several cache servers avoid a single point of failure.', firstPage: 14, lastPage: 14, evidence: 'e' },
      ],
    },
  ],
  diagrams: ['flowchart LR\n  A --> B'],
  leftOut: 'Left out: the Memcached snippet.',
  youtubeUrl: 'https://www.youtube.com/results?search_query=cache+eviction',
  buildsOn: [{ mapItemId: 5, title: 'Database replication', bookTitle: 'System Design Interview', why: 'Reads used to hit the database.' }],
  quickCheck: [
    { id: 31, type: 'SHORT', prompt: 'Which eviction policy is most common?', pointId: 1, bookTitle: 'System Design Interview', chapterTitle: 'Chapter 1' },
    { id: 32, type: 'SHORT', prompt: 'Why use several cache servers?', pointId: 2, bookTitle: 'System Design Interview', chapterTitle: 'Chapter 1' },
  ],
  miniDesign: null,
  newPointsFound: 0,
}

const grade = (verdict: Verdict): GradeResult => ({
  attemptId: 1,
  verdict,
  feedback: 'Good.',
  notInBook: false,
  modelAnswer: 'LRU',
  firstPage: 15,
  lastPage: 15,
  bookTitle: 'System Design Interview',
})

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/lesson/7']}>
      <Routes>
        <Route path="/lesson/:id" element={<LessonPage pollMs={10} />} />
        <Route path="/" element={<p>home</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('LessonPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('showsPreparingThenTheLesson', async () => {
    mockFetch([
      { method: 'POST', path: '/api/map-items/7/lesson/open', body: { ...ready, status: 'PREPARING' } },
      { path: '/api/map-items/7/lesson', body: ready },
    ])
    renderPage()
    expect(await screen.findByText('Preparing your lesson…')).toBeInTheDocument()
    expect(await screen.findByText('The idea')).toBeInTheDocument()
    expect(screen.getByText(/Left out: the Memcached snippet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Watch on YouTube/ })).toHaveAttribute('href', ready.youtubeUrl)
    expect(screen.getByRole('link', { name: 'Database replication' })).toHaveAttribute('href', '/lesson/5')
  })

  it('quickCheckAnswersThenMarksLearned', async () => {
    const api = mockFetch([
      { method: 'POST', path: '/api/map-items/7/lesson/open', body: ready },
      { method: 'POST', path: /\/api\/questions\/\d+\/answer/, body: grade('CORRECT') },
      { method: 'POST', path: '/api/map-items/7/learned', status: 204 },
    ])
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Start quick check (2)' }))
    for (let i = 0; i < 2; i++) {
      await userEvent.type(screen.getByLabelText('Your answer'), 'LRU')
      await userEvent.click(screen.getByRole('button', { name: 'Check' }))
      await userEvent.click(await screen.findByRole('button', { name: 'Next' }))
    }
    expect(await screen.findByText('Section learned — its points are now in your revision.')).toBeInTheDocument()
    expect(api.calls.some((c) => c.method === 'POST' && c.url === '/api/map-items/7/learned')).toBe(true)
  })

  it('failedLessonOffersPagesAndTryAgain', async () => {
    mockFetch([
      {
        method: 'POST',
        path: '/api/map-items/7/lesson/open',
        body: { ...ready, status: 'FAILED', failureReason: 'The AI is unreachable right now.' },
      },
      { method: 'POST', path: '/api/map-items/7/lesson/rewrite', body: { ...ready, status: 'PREPARING' } },
      { path: '/api/map-items/7/lesson', body: ready },
    ])
    renderPage()
    expect(await screen.findByText('The AI is unreachable right now.')).toBeInTheDocument()
    expect(screen.getByText('Read pp. 13–15 in System Design Interview instead.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('The idea')).toBeInTheDocument()
  })

  it('emptyQuickCheckStillOffersMarkLearned', async () => {
    mockFetch([
      { method: 'POST', path: '/api/map-items/7/lesson/open', body: { ...ready, quickCheck: [] } },
      { method: 'POST', path: '/api/map-items/7/learned', status: 204 },
    ])
    renderPage()
    expect(await screen.findByText('No quick-check questions for this section.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Mark learned without the check' }))
    expect(await screen.findByText('Section learned — its points are now in your revision.')).toBeInTheDocument()
  })

  it('learnedSectionOffersPracticeAndAnnouncesNewPoints', async () => {
    mockFetch([{ method: 'POST', path: '/api/map-items/7/lesson/open', body: { ...ready, learned: true, newPointsFound: 2 } }])
    renderPage()
    expect(await screen.findByText('2 new points found in this section, now in your revision.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Practice (optional)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark learned without the check' })).toBeNull()
  })

  it('removedSectionSaysSo', async () => {
    mockFetch([{ method: 'POST', path: '/api/map-items/7/lesson/open', status: 404, body: { error: 'x' } }])
    renderPage()
    expect(await screen.findByText("This section isn't in your syllabus.")).toBeInTheDocument()
  })
})
