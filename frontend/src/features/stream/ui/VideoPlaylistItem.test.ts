import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { VideoRecord } from '@/features/stream/types'
import VideoPlaylistItem from '@/features/stream/ui/VideoPlaylistItem.vue'

const baseVideo: VideoRecord = {
  uploadId: '550e8400-e29b-41d4-a716-446655440000',
  status: 'PLAY_READY',
  fileName: 'demo.mp4',
  createdAt: '2026-06-27T12:00:00Z',
  updatedAt: '2026-06-27T12:00:00Z',
}

describe('VideoPlaylistItem', () => {
  it('shows filename, upload id, and status badge', () => {
    const wrapper = mount(VideoPlaylistItem, {
      props: { video: baseVideo, selected: false },
    })

    expect(wrapper.text()).toContain('demo.mp4')
    expect(wrapper.text()).toContain(baseVideo.uploadId)
    expect(wrapper.get('[data-testid="playlist-item-status-PLAY_READY"]').text()).toBe('Ready')
  })

  it('emits select with the video record on click', async () => {
    const wrapper = mount(VideoPlaylistItem, {
      props: { video: baseVideo, selected: false },
    })

    await wrapper.get(`[data-testid="playlist-item-${baseVideo.uploadId}"]`).trigger('click')
    expect(wrapper.emitted('select')).toEqual([[baseVideo]])
  })

  it('highlights selected playable videos', () => {
    const wrapper = mount(VideoPlaylistItem, {
      props: { video: baseVideo, selected: true },
    })

    expect(wrapper.get(`[data-testid="playlist-item-${baseVideo.uploadId}"]`).classes()).toContain(
      'border-sky-300',
    )
  })

  it('shows progress hint for in-progress videos', () => {
    const wrapper = mount(VideoPlaylistItem, {
      props: {
        video: { ...baseVideo, status: 'TRANSCODING_IN_PROGRESS' },
        selected: false,
      },
    })

    expect(wrapper.get('[data-testid="playlist-item-progress-hint"]').text()).toContain(
      'Processing',
    )
  })

  it('shows failure hint and red styling for failed videos', () => {
    const wrapper = mount(VideoPlaylistItem, {
      props: {
        video: { ...baseVideo, status: 'FAILED' },
        selected: true,
      },
    })

    expect(wrapper.get('[data-testid="playlist-item-failed-hint"]').text()).toContain(
      'Transcoding failed',
    )
    expect(wrapper.get(`[data-testid="playlist-item-${baseVideo.uploadId}"]`).classes()).toContain(
      'border-red-300',
    )
  })
})
