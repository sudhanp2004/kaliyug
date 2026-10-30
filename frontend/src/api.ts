// Typed client for the backend. Field names mirror the Java DTOs.

export type Verdict = 'CORRECT' | 'PARTIAL' | 'WRONG'
export type ResourceStatus = 'INGESTING' | 'READY' | 'FAILED'
export type QuestionType = 'SHORT' | 'QUICKFIRE'

export type Bar = { knownWell: number; total: number; percent: number }
export type BookBar = {
  resourceId: number
  title: string
  status: ResourceStatus
  failureReason: string | null
  bar: Bar
  /** Has counted points and every one is known well. */
  mastered: boolean
}
export type Progress = {
  overall: Bar
  books: BookBar[]
  wins: { weakPointsFixedThisWeek: number; solidChapters: string[] }
  dueCount: number
  preparing: boolean
}

export type QueueItem = {
  id: number
  title: string
  bookTitle: string
  chapterTitle: string
  startPage: number
  endPage: number
}
export type Queue = {
  items: QueueItem[]
  remaining: number
  preparing: boolean
  /** Sections skipped and not yet back in the list. */
  skipped: number
  /** There are books, but learning is switched off for all of them. */
  allPaused: boolean
}

export type QuestionView = {
  id: number
  type: QuestionType
  prompt: string
  pointId: number
  bookTitle: string
  chapterTitle: string
}
export type ProblemView = { id: number; title: string; requirements: string[]; chapterTitle: string }
export type RevisionItem =
  | { type: 'QUESTION'; question: QuestionView; problem: null }
  | { type: 'PROBLEM'; question: null; problem: ProblemView }
  | { type: 'DONE'; question: null; problem: null }

export type GradeResult = {
  attemptId: number
  verdict: Verdict
  feedback: string
  notInBook: boolean
  modelAnswer: string
  firstPage: number
  lastPage: number
  bookTitle: string
}

export type Problem = { id: number; title: string; requirements: string[]; chapterTitle: string; bookTitle: string }
export type DesignSubmission = {
  nodes: { id: string; label: string }[]
  edges: { from: string; to: string; label: string }[]
  notes: string
}
export type PointResult = {
  pointId: number
  text: string
  firstPage: number
  lastPage: number
  verdict: Verdict
  attemptId: number
}
export type DesignResult = { feedback: string; differences: string; points: PointResult[] }

export type LessonStatus = 'PREPARING' | 'READY' | 'FAILED'
export type LessonPart = { text: string; firstPage: number; lastPage: number }
export type RememberItem = LessonPart & { evidence: string }
export type LessonBlock = { idea: LessonPart; howItWorks: LessonPart; remember: RememberItem[] }
export type LessonLink = { mapItemId: number; title: string; bookTitle: string; why: string }
export type MiniDesign = { task: string; requirements: string[] }
export type Lesson = {
  mapItemId: number
  status: LessonStatus
  failureReason: string | null
  sectionTitle: string
  bookTitle: string
  chapterTitle: string
  startPage: number
  endPage: number
  learned: boolean
  /** null until the lesson is READY. */
  estimatedMinutes: number | null
  blocks: LessonBlock[]
  diagrams: string[]
  leftOut: string
  youtubeUrl: string | null
  buildsOn: LessonLink[]
  quickCheck: QuestionView[]
  miniDesign: MiniDesign | null
  /** Points the coverage check added to an already learned section. */
  newPointsFound: number
}

export type ResourceInfo = {
  id: number
  title: string
  status: ResourceStatus
  failureReason: string | null
  totalPages: number
  itemsReady: number
  itemsTotal: number
  learningOn: boolean
  revisionOn: boolean
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (body instanceof FormData) {
    init.body = body
  } else if (body !== undefined) {
    init.body = JSON.stringify(body)
    init.headers = { 'Content-Type': 'application/json' }
  }
  let res: Response
  try {
    res = await fetch(url, init)
  } catch {
    throw new ApiError(0, "Can't reach the app's server. Is it still running?")
  }
  if (!res.ok) {
    let message = `Something went wrong (HTTP ${res.status}).`
    try {
      const data = await res.json()
      if (data && typeof data.error === 'string') message = data.error
    } catch {
      // keep the generic message
    }
    throw new ApiError(res.status, message)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

const get = <T>(url: string) => request<T>('GET', url)
const post = <T>(url: string, body?: unknown) => request<T>('POST', url, body)

export const getProgress = () => get<Progress>('/api/progress')
export const getQueue = (limit = 8) => get<Queue>(`/api/queue?limit=${limit}`)
export const markLearned = (id: number) => post<void>(`/api/map-items/${id}/learned`)
export const skipItem = (id: number) => post<void>(`/api/map-items/${id}/skip`)
export const hideItem = (id: number) => post<void>(`/api/map-items/${id}/hide`)
export const unhideItem = (id: number) => post<void>(`/api/map-items/${id}/unhide`)
export const getHidden = () => get<QueueItem[]>('/api/map-items/hidden')

export const getNext = (answered: number, exclude: number[]) => {
  const params = new URLSearchParams({ answered: String(answered) })
  if (exclude.length > 0) params.set('exclude', exclude.join(','))
  return get<RevisionItem>(`/api/revision/next?${params.toString().replace(/%2C/g, ',')}`)
}
export const submitAnswer = (questionId: number, answer: string) =>
  post<GradeResult>(`/api/questions/${questionId}/answer`, { answer })
export const overrideAttempt = (attemptId: number) => post<void>(`/api/attempts/${attemptId}/override`)
export const flagQuestion = (questionId: number) => post<void>(`/api/questions/${questionId}/flag`)

export const getProblem = (id: number) => get<Problem>(`/api/problems/${id}`)
export const submitDesign = (id: number, submission: DesignSubmission) =>
  post<DesignResult>(`/api/problems/${id}/submit`, submission)

export const openLesson = (id: number) => post<Lesson>(`/api/map-items/${id}/lesson/open`)
export const getLesson = (id: number) => get<Lesson>(`/api/map-items/${id}/lesson`)
export const rewriteLesson = (id: number) => post<Lesson>(`/api/map-items/${id}/lesson/rewrite`)
export const submitLessonDesign = (id: number, submission: DesignSubmission) =>
  post<DesignResult>(`/api/map-items/${id}/lesson/design`, submission)
export const prepareUpcoming = () => post<void>('/api/lessons/prepare-upcoming')

export const getResources = () => get<ResourceInfo[]>('/api/resources')
export const getAvailable = () => get<string[]>('/api/resources/available')
export const importBook = (fileName: string) => post<ResourceInfo>('/api/resources/import', { fileName })
export const uploadBook = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return post<ResourceInfo>('/api/resources', form)
}
export const retryResource = (id: number) => post<void>(`/api/resources/${id}/retry`)
export const setLearning = (id: number, on: boolean) => post<void>(`/api/resources/${id}/learning`, { on })
export const setRevision = (id: number, on: boolean) => post<void>(`/api/resources/${id}/revision`, { on })

export const pagesLabel = (first: number, last: number) => (first === last ? `p. ${first}` : `pp. ${first}–${last}`)
