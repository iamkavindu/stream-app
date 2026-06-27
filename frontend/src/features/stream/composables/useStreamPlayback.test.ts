import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { ApiError } from '@/shared/api/apiError'
import {
  STREAM_POLL_INTERVAL_MS,
  useStreamPlayback,
} from '@/features/stream/composables/useStreamPlayback'
import type { VideoRecord } from '@/features/stream/types'

vi.mock('@/features/stream/api/streamApi', () => ({
  listVideos: vi.fn(),
  getSignedStreamUrl: vi.fn(),
}))

import { getSignedStreamUrl, listVideos } from '@/features/stream/api/streamApi'

const baseVideo: VideoRecord = {
  uploadId: '550e8400-e29b-41d4-a716-446655440000',
  status: 'PLAY_READY',
  fileName: 'demo.mp4',
  createdAt: '2026-06-27T12:00:00Z',
  updatedAt: '2026-06-27T12:00:00Z',
}

function mountPlayback() {
  let playback: ReturnType<typeof useStreamPlayback> | null = null
  const Wrapper = defineComponent({
    setup() {
      playback = useStreamPlayback()
      return () => null
    },
  })
  mount(Wrapper)
  return playback!
}

describe('useStreamPlayback', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('loads videos on mount', async () => {
    vi.mocked(listVideos).mockResolvedValue([baseVideo])

    const playback = mountPlayback()
    await vi.waitFor(() => {
      expect(playback.videos.value).toHaveLength(1)
    })

    expect(listVideos).toHaveBeenCalledTimes(1)
    expect(playback.shouldPoll.value).toBe(false)
  })

  it('polls while any video is in progress and stops when all are terminal', async () => {
    vi.mocked(listVideos)
      .mockResolvedValueOnce([
        { ...baseVideo, status: 'TRANSCODING_IN_PROGRESS', uploadId: 'a' },
      ])
      .mockResolvedValueOnce([{ ...baseVideo, status: 'PLAY_READY', uploadId: 'a' }])

    mountPlayback()
    await vi.waitFor(() => {
      expect(listVideos).toHaveBeenCalledTimes(1)
    })

    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS)
    expect(listVideos).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS)
    expect(listVideos).toHaveBeenCalledTimes(2)
  })

  it('surfaces API errors separately from non-playable status messages', async () => {
    vi.mocked(listVideos).mockResolvedValue([baseVideo])
    vi.mocked(getSignedStreamUrl).mockRejectedValue(
      new ApiError('Video is not ready', { status: 409, detail: 'Video is not ready' }, 409),
    )

    const playback = mountPlayback()
    await vi.waitFor(() => {
      expect(playback.videos.value).toHaveLength(1)
    })

    await playback.selectVideo(baseVideo)
    expect(playback.playerError.value).toContain('Stream not ready')

    await playback.selectVideo({
      ...baseVideo,
      status: 'FAILED',
    })
    expect(playback.playerError.value).toContain('Transcoding failed')
  })

  it('loads signed manifest URL for a playable video', async () => {
    const signedUrl = 'http://localhost:4566/streamapp-streams/demo/index.m3u8?sig=abc'
    vi.mocked(listVideos).mockResolvedValue([baseVideo])
    vi.mocked(getSignedStreamUrl).mockResolvedValue({
      uploadId: baseVideo.uploadId,
      objectKey: `${baseVideo.uploadId}/index.m3u8`,
      signedUrl,
    })

    const playback = mountPlayback()
    await vi.waitFor(() => {
      expect(playback.videos.value).toHaveLength(1)
    })

    await playback.selectVideo(baseVideo)

    expect(getSignedStreamUrl).toHaveBeenCalledWith(baseVideo.uploadId)
    expect(playback.manifestUrl.value).toBe(signedUrl)
    expect(playback.playerError.value).toBeNull()
  })

  it('sets listError when the library fetch fails', async () => {
    vi.mocked(listVideos).mockRejectedValue(new Error('Network down'))

    const playback = mountPlayback()
    await vi.waitFor(() => {
      expect(playback.listError.value).toBe('Network down')
    })

    expect(playback.listLoading.value).toBe(false)
  })

  it('formats 404 stream URL errors', async () => {
    vi.mocked(listVideos).mockResolvedValue([baseVideo])
    vi.mocked(getSignedStreamUrl).mockRejectedValue(
      new ApiError('Unknown upload', { status: 404 }, 404),
    )

    const playback = mountPlayback()
    await vi.waitFor(() => {
      expect(playback.videos.value).toHaveLength(1)
    })

    await playback.selectVideo(baseVideo)
    expect(playback.playerError.value).toContain('Video not found')
  })

  it('stops polling on unmount', async () => {
    vi.mocked(listVideos).mockResolvedValue([
      { ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' },
    ])

    let playback: ReturnType<typeof useStreamPlayback> | null = null
    const Wrapper = defineComponent({
      setup() {
        playback = useStreamPlayback()
        return () => null
      },
    })
    const wrapper = mount(Wrapper)
    await vi.waitFor(() => {
      expect(listVideos).toHaveBeenCalledTimes(1)
    })

    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS)
    expect(listVideos).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS * 2)
    expect(listVideos).toHaveBeenCalledTimes(2)
  })
})
