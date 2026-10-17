import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Without vitest globals, Testing Library can't register its own cleanup.
afterEach(() => cleanup())

// jsdom lacks the layout APIs React Flow measures with.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver

class DOMMatrixReadOnlyStub {
  m22: number
  constructor(transform?: string) {
    const scale = transform?.match(/scale\(([1-9.])\)/)?.[1]
    this.m22 = scale !== undefined ? Number(scale) : 1
  }
}
globalThis.DOMMatrixReadOnly ??= DOMMatrixReadOnlyStub as unknown as typeof DOMMatrixReadOnly
