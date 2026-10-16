import { afterEach, expect, it, vi } from 'vitest'
import { retryProcessing } from './processingRetryApi'
afterEach(() => vi.unstubAllGlobals())
it('posts a processing action without registering or uploading bytes', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 202 }))
  await retryProcessing('video')
  expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/v1/videos/video/processing-retries', { method: 'POST' })
})
it('preserves ProblemDetail conflicts for display', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409,
    headers: { get: () => 'application/problem+json' }, json: async () => ({ detail: 'Original source is missing' }) }))
  await expect(retryProcessing('video')).rejects.toThrow('Original source is missing')
})
