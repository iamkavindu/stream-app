import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/apiError'
import { createSignedUpload, retryUpload } from '@/features/upload/api/videoApi'

describe('createSignedUpload', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts file metadata and returns signed upload response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        uploadId: '550e8400-e29b-41d4-a716-446655440000',
        signedUrl: 'http://localhost:4566/streamapp-uploads/object',
        fileName: 'demo.mp4',
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const response = await createSignedUpload({
      fileName: 'demo.mp4',
      sha256Hex: 'abc123',
    })

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/videos', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: 'demo.mp4', sha256Hex: 'abc123' }),
    })
    expect(response.uploadId).toBe('550e8400-e29b-41d4-a716-446655440000')
  })

  it('throws ApiError with problem detail for duplicate uploads', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        headers: {
          get: () => 'application/problem+json',
        },
        json: async () => ({
          type: 'https://stream-app.dev/problems/duplicate-video-upload',
          title: 'Duplicate Video Upload',
          status: 409,
          detail: 'Video is already uploaded: demo.mp4',
        }),
      }),
    )

    await expect(
      createSignedUpload({ fileName: 'demo.mp4', sha256Hex: 'abc123' }),
    ).rejects.toMatchObject({
      message: 'Video is already uploaded: demo.mp4',
      status: 409,
    })
  })

  it('throws ApiError when backend responds with error status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        headers: { get: () => '' },
      }),
    )

    await expect(
      createSignedUpload({ fileName: 'demo.mp4', sha256Hex: 'abc123' }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

describe('retryUpload', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renews the retained upload instead of registering another video', async () => {
    const decision = { uploadId: 'same-id', fileName: 'demo.mp4', sourceReceived: false, signedUrl: 'fresh-url' }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => decision })
    vi.stubGlobal('fetch', fetchMock)
    expect(await retryUpload('same-id')).toEqual(decision)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/videos/same-id/upload-url', { method: 'POST' })
  })

  it('returns a received decision with no upload URL', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ uploadId: 'same-id', fileName: 'demo.mp4', sourceReceived: true, signedUrl: null }),
    }))
    expect((await retryUpload('same-id')).sourceReceived).toBe(true)
  })

  it('surfaces a non-retryable session as an API error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 409, headers: { get: () => 'application/problem+json' },
      json: async () => ({ status: 409, detail: 'Upload cannot be retried' }),
    }))
    await expect(retryUpload('same-id')).rejects.toMatchObject({ status: 409, message: 'Upload cannot be retried' })
  })
})
