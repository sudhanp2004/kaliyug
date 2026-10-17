import { vi } from 'vitest'

export type Route = {
  method?: string
  path: string | RegExp
  status?: number
  body?: unknown
}

/** Replaces fetch with a router over canned responses and records every request. */
export function mockFetch(routes: Route[]) {
  const calls: { method: string; url: string; body?: string }[] = []
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString()
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push({ method, url, body: typeof init?.body === 'string' ? init.body : undefined })
    const path = url.split('?')[0]
    const route = [...routes].reverse().find(
      (r) =>
        (r.method ?? 'GET') === method &&
        (typeof r.path === 'string' ? r.path === path : r.path.test(path)),
    )
    if (!route) {
      return new Response(JSON.stringify({ error: `no mock for ${method} ${url}` }), { status: 500 })
    }
    const status = route.status ?? 200
    const body = status === 204 ? null : JSON.stringify(route.body ?? {})
    return new Response(body, { status, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fn)
  return {
    calls,
    /** Adds or overrides a route (later routes win). */
    add(route: Route) {
      routes.push(route)
    },
  }
}
