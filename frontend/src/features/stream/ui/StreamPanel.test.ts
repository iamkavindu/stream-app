import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import type { VideoRecord } from '@/features/stream/types'
import StreamPanel from '@/features/stream/ui/StreamPanel.vue'

const baseVideo: VideoRecord = {
  uploadId: '550e8400-e29b-41d4-a716-446655440000',
  status: 'PLAY_READY',
  fileName: 'demo.mp4',
  createdAt: '2026-06-27T12:00:00Z',
  updatedAt: '2026-06-27T12:00:00Z',
}

const videos = ref<VideoRecord[]>([])
const listLoading = ref(false)
const listError = ref<string | null>(null)
const selectedUploadId = ref<string | null>(null)
const manifestUrl = ref<string | null>(null)
const playerLoading = ref(false)
const playerError = ref<string | null>(null)
const shouldPoll = ref(false)
const loadVideos = vi.fn()
const selectVideo = vi.fn()

vi.mock('@/features/stream/composables/useStreamPlayback', () => ({
  useStreamPlayback: () => ({
    videos,
    listLoading,
    listError,
    selectedUploadId,
    manifestUrl,
    playerLoading,
    playerError,
    shouldPoll,
    loadVideos,
    selectVideo,
  }),
}))

describe('StreamPanel', () => {
  it('shows empty playlist message', () => {
    videos.value = []
    listLoading.value = false
    listError.value = null

    const wrapper = mount(StreamPanel, {
      global: { stubs: { VideoPlaylistItem: true, HlsPlayer: true } },
    })

    expect(wrapper.get('[data-testid="playlist-empty"]').text()).toContain('No videos yet')
  })

  it('shows polling indicator while videos are processing', () => {
    videos.value = [{ ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' }]
    shouldPoll.value = true

    const wrapper = mount(StreamPanel, {
      global: { stubs: { VideoPlaylistItem: true, HlsPlayer: true } },
    })

    expect(wrapper.get('[data-testid="list-polling-indicator"]').text()).toContain(
      'Auto-refreshing',
    )
  })

  it('shows list error alert', () => {
    videos.value = []
    listError.value = 'Network down'

    const wrapper = mount(StreamPanel, {
      global: { stubs: { VideoPlaylistItem: true, HlsPlayer: true } },
    })

    expect(wrapper.get('[data-testid="list-error"]').text()).toContain('Network down')
  })

  it('shows player placeholder until a video is selected', () => {
    videos.value = [baseVideo]
    selectedUploadId.value = null

    const wrapper = mount(StreamPanel, {
      global: { stubs: { VideoPlaylistItem: true, HlsPlayer: true } },
    })

    expect(wrapper.get('[data-testid="player-placeholder"]').exists()).toBe(true)
  })

  it('shows player error for non-playable selection', () => {
    videos.value = [baseVideo]
    selectedUploadId.value = baseVideo.uploadId
    playerLoading.value = false
    playerError.value = 'Transcoding failed for this video'
    manifestUrl.value = null

    const wrapper = mount(StreamPanel, {
      global: { stubs: { VideoPlaylistItem: true, HlsPlayer: true } },
    })

    expect(wrapper.get('[data-testid="player-error"]').text()).toContain('Transcoding failed')
  })

  it('renders HlsPlayer when manifest URL is available', () => {
    videos.value = [baseVideo]
    selectedUploadId.value = baseVideo.uploadId
    playerLoading.value = false
    playerError.value = null
    manifestUrl.value = 'http://localhost:4566/index.m3u8'

    const wrapper = mount(StreamPanel, {
      global: { stubs: { VideoPlaylistItem: true } },
    })

    expect(wrapper.find('[data-testid="hls-video"]').exists()).toBe(true)
  })

  it('calls loadVideos when refresh is clicked', async () => {
    videos.value = [baseVideo]

    const wrapper = mount(StreamPanel, {
      global: { stubs: { VideoPlaylistItem: true, HlsPlayer: true } },
    })

    await wrapper.get('[data-testid="refresh-videos"]').trigger('click')
    expect(loadVideos).toHaveBeenCalled()
  })
})
