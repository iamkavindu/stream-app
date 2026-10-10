import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ApiError } from '@/shared/api/apiError'
import {
  STREAM_POLL_INTERVAL_MS,
  STREAM_POLL_MAX_INTERVAL_MS,
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

const wrappers: ReturnType<typeof mount>[] = []
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function mountPlayback() {
  let playback: ReturnType<typeof useStreamPlayback> | null = null
  const Wrapper = defineComponent({
    setup() {
      playback = useStreamPlayback()
      return () => null
    },
  })
  wrappers.push(mount(Wrapper))
  return playback!
}

describe('useStreamPlayback', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetAllMocks()
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
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
      .mockResolvedValueOnce([{ ...baseVideo, status: 'TRANSCODING_IN_PROGRESS', uploadId: 'a' }])
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
    vi.mocked(listVideos).mockResolvedValue([{ ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' }])

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

  it.each(['success', 'failure'] as const)(
    'ignores a late %s for the previous selection',
    async (outcome) => {
      vi.mocked(listVideos).mockResolvedValue([baseVideo])
      const first = deferred<Awaited<ReturnType<typeof getSignedStreamUrl>>>()
      const second = deferred<Awaited<ReturnType<typeof getSignedStreamUrl>>>()
      vi.mocked(getSignedStreamUrl)
        .mockReturnValueOnce(first.promise)
        .mockReturnValueOnce(second.promise)
      const playback = mountPlayback()
      await flushPromises()
      const oldSelection = playback.selectVideo(baseVideo)
      const currentSelection = playback.selectVideo({ ...baseVideo, uploadId: 'new' })
      if (outcome === 'success')
        first.resolve({ uploadId: baseVideo.uploadId, objectKey: 'old', signedUrl: 'old' })
      else first.reject(new Error('Old failure'))
      await oldSelection
      expect(playback.playerLoading.value).toBe(true)
      expect(playback.manifestUrl.value).toBeNull()
      expect(playback.playerError.value).toBeNull()
      second.resolve({ uploadId: 'new', objectKey: 'new', signedUrl: 'current' })
      await currentSelection
      expect(playback.manifestUrl.value).toBe('current')
      expect(playback.playerLoading.value).toBe(false)
    },
  )

  it('invalidates a pending URL when a non-playable video is selected', async () => {
    vi.mocked(listVideos).mockResolvedValue([baseVideo])
    const pending = deferred<Awaited<ReturnType<typeof getSignedStreamUrl>>>()
    vi.mocked(getSignedStreamUrl).mockReturnValue(pending.promise)
    const playback = mountPlayback()
    await flushPromises()
    const selection = playback.selectVideo(baseVideo)
    await playback.selectVideo({ ...baseVideo, status: 'FAILED' })
    pending.resolve({ uploadId: baseVideo.uploadId, objectKey: 'old', signedUrl: 'old' })
    await selection
    expect(playback.manifestUrl.value).toBeNull()
    expect(playback.playerLoading.value).toBe(false)
    expect(playback.playerError.value).toContain('Transcoding failed')
  })

  it('shares a slow poll with refresh and schedules the next poll after completion', async () => {
    const active = { ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' as const }
    const pending = deferred<VideoRecord[]>()
    vi.mocked(listVideos)
      .mockResolvedValueOnce([active])
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValue([active])
    const playback = mountPlayback()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS)
    const refresh = playback.loadVideos()
    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS * 4)
    expect(listVideos).toHaveBeenCalledTimes(2)
    pending.resolve([active])
    await refresh
    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS - 1)
    expect(listVideos).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(listVideos).toHaveBeenCalledTimes(3)
  })

  it('backs off failed polls to a cap and resets the delay after recovery', async () => {
    const active = { ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' as const }
    vi.mocked(listVideos).mockResolvedValueOnce([active]).mockRejectedValue(new Error('Offline'))
    mountPlayback()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS)
    let calls = 2
    for (const delay of [8000, 16000, STREAM_POLL_MAX_INTERVAL_MS, STREAM_POLL_MAX_INTERVAL_MS]) {
      await vi.advanceTimersByTimeAsync(delay - 1)
      expect(listVideos).toHaveBeenCalledTimes(calls)
      await vi.advanceTimersByTimeAsync(1)
      expect(listVideos).toHaveBeenCalledTimes(++calls)
    }
    vi.mocked(listVideos).mockResolvedValue([active])
    await vi.advanceTimersByTimeAsync(STREAM_POLL_MAX_INTERVAL_MS)
    expect(listVideos).toHaveBeenCalledTimes(++calls)
    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS)
    expect(listVideos).toHaveBeenCalledTimes(++calls)
  })

  it.each(['missing', 'failed'] as const)(
    'clears obsolete playback when the library reports %s',
    async (state) => {
      vi.mocked(listVideos).mockResolvedValue([baseVideo])
      vi.mocked(getSignedStreamUrl).mockResolvedValue({
        uploadId: baseVideo.uploadId,
        objectKey: 'key',
        signedUrl: 'url',
      })
      const playback = mountPlayback()
      await flushPromises()
      await playback.selectVideo(baseVideo)
      vi.mocked(listVideos).mockResolvedValue(
        state === 'missing' ? [] : [{ ...baseVideo, status: 'FAILED' }],
      )
      await playback.loadVideos()
      expect(playback.manifestUrl.value).toBeNull()
      expect(playback.playerLoading.value).toBe(false)
      if (state === 'missing') expect(playback.selectedUploadId.value).toBeNull()
      else expect(playback.playerError.value).toContain('Transcoding failed')
    },
  )

  it('loads the selected video when polling observes it become ready', async () => {
    const active = { ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' as const }
    vi.mocked(listVideos).mockResolvedValueOnce([active]).mockResolvedValue([baseVideo])
    vi.mocked(getSignedStreamUrl).mockResolvedValue({
      uploadId: baseVideo.uploadId,
      objectKey: 'key',
      signedUrl: 'ready',
    })
    const playback = mountPlayback()
    await flushPromises()
    await playback.selectVideo(active)
    await vi.advanceTimersByTimeAsync(STREAM_POLL_INTERVAL_MS)
    expect(playback.manifestUrl.value).toBe('ready')
    expect(playback.playerError.value).toBeNull()
  })

  it('does not restart polling or update state when a list response arrives after unmount', async () => {
    const pending = deferred<VideoRecord[]>()
    vi.mocked(listVideos).mockReturnValue(pending.promise)
    const playback = mountPlayback()
    wrappers[wrappers.length - 1]!.unmount()
    pending.resolve([{ ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' }])
    await flushPromises()
    await vi.advanceTimersByTimeAsync(STREAM_POLL_MAX_INTERVAL_MS * 2)
    expect(listVideos).toHaveBeenCalledTimes(1)
    expect(playback.videos.value).toEqual([])
    await playback.loadVideos()
    await playback.selectVideo(baseVideo)
    expect(getSignedStreamUrl).not.toHaveBeenCalled()
  })

  it.each(['success', 'failure'] as const)('ignores a URL %s after unmount', async (outcome) => {
    vi.mocked(listVideos).mockResolvedValue([baseVideo])
    const pending = deferred<Awaited<ReturnType<typeof getSignedStreamUrl>>>()
    vi.mocked(getSignedStreamUrl).mockReturnValue(pending.promise)
    const playback = mountPlayback()
    await flushPromises()
    const selection = playback.selectVideo(baseVideo)
    wrappers[wrappers.length - 1]!.unmount()
    if (outcome === 'success')
      pending.resolve({ uploadId: baseVideo.uploadId, objectKey: 'key', signedUrl: 'old' })
    else pending.reject(new Error('Late failure'))
    await selection
    expect(playback.manifestUrl.value).toBeNull()
    expect(playback.playerError.value).toBeNull()
  })
})
