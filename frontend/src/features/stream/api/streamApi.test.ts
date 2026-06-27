import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/apiError'
import { getSignedStreamUrl, listVideos } from '@/features/stream/api/streamApi'

describe('streamApi', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('listVideos', () => {
    it('fetches all videos', async () => {
      const videos = [
        {
          uploadId: '550e8400-e29b-41d4-a716-446655440000',
          status: 'PLAY_READY',
          fileName: 'demo.mp4',
          createdAt: '2026-06-27T12:00:00Z',
          updatedAt: '2026-06-27T12:00:00Z',
        },
      ]

      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({
          ok: true,
          json: async () => videos,
        }),
      )

      const result = await listVideos()

      expect(fetch).toHaveBeenCalledWith('/api/v1/videos')
      expect(result).toEqual(videos)
    })

    it('throws ApiError when list fails', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({
          ok: false,
          status: 500,
          headers: { get: () => '' },
        }),
      )

      await expect(listVideos()).rejects.toBeInstanceOf(ApiError)
    })
  })

  describe('getSignedStreamUrl', () => {
    it('fetches signed HLS manifest URL for uploadId', async () => {
      const uploadId = '550e8400-e29b-41d4-a716-446655440000'
      const response = {
        uploadId,
        objectKey: `${uploadId}/index.m3u8`,
        signedUrl: `http://localhost:4566/streamapp-streams/${uploadId}/index.m3u8?sig=abc`,
      }

      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({
          ok: true,
          json: async () => response,
        }),
      )

      const result = await getSignedStreamUrl(uploadId)

      expect(fetch).toHaveBeenCalledWith(`/api/v1/videos/${uploadId}/signed-url`)
      expect(result.signedUrl).toContain('index.m3u8')
    })

    it('throws ApiError when video is not ready', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({
          ok: false,
          status: 409,
          headers: { get: () => 'application/problem+json' },
          json: async () => ({
            type: 'https://stream-app.dev/problems/video-not-ready',
            title: 'Video Not Ready',
            status: 409,
            detail: 'Video is not ready for playback.',
          }),
        }),
      )

      await expect(
        getSignedStreamUrl('550e8400-e29b-41d4-a716-446655440000'),
      ).rejects.toMatchObject({
        status: 409,
      })
    })
  })
})
