import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, getErrorMessage, readApiError } from '@/shared/api/apiError'

describe('readApiError', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('parses RFC 7807 problem detail from the response body', async () => {
    const response = new Response(
      JSON.stringify({
        type: 'https://stream-app.dev/problems/duplicate-video-upload',
        title: 'Duplicate Video Upload',
        status: 409,
        detail: 'Video is already uploaded: demo.mp4',
      }),
      {
        status: 409,
        headers: { 'Content-Type': 'application/problem+json' },
      },
    )

    const error = await readApiError(response)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.message).toBe('Video is already uploaded: demo.mp4')
    expect(error.status).toBe(409)
    expect(error.problem?.title).toBe('Duplicate Video Upload')
  })

  it('falls back to status when body is not JSON', async () => {
    const response = new Response(null, { status: 500 })

    const error = await readApiError(response)

    expect(error.message).toBe('Request failed (500)')
  })
})

describe('getErrorMessage', () => {
  it('returns ApiError message', () => {
    expect(getErrorMessage(new ApiError('Duplicate upload'))).toBe('Duplicate upload')
  })

  it('returns generic message for unknown errors', () => {
    expect(getErrorMessage('nope')).toBe('An unexpected error occurred.')
  })
})
