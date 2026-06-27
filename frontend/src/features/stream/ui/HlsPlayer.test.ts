import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Hls from 'hls.js'
import HlsPlayer from '@/features/stream/ui/HlsPlayer.vue'

const hlsInstance = {
  loadSource: vi.fn(),
  attachMedia: vi.fn(),
  destroy: vi.fn(),
  on: vi.fn(),
}

vi.mock('hls.js', () => ({
  default: class MockHls {
    static isSupported = vi.fn(() => true)
    static Events = { ERROR: 'hlsError' }
    static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' }
    static ErrorDetails = {
      MANIFEST_LOAD_ERROR: 'manifestLoadError',
      FRAG_LOAD_ERROR: 'fragLoadError',
      BUFFER_ADD_CODEC_ERROR: 'bufferAddCodecError',
    }

    loadSource = hlsInstance.loadSource
    attachMedia = hlsInstance.attachMedia
    destroy = hlsInstance.destroy
    on = hlsInstance.on

    constructor() {
      return hlsInstance
    }
  },
}))

describe('HlsPlayer', () => {
  beforeEach(() => {
    vi.mocked(Hls.isSupported).mockReturnValue(true)
    hlsInstance.loadSource.mockClear()
    hlsInstance.attachMedia.mockClear()
    hlsInstance.destroy.mockClear()
    hlsInstance.on.mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads source via hls.js when supported', async () => {
    mount(HlsPlayer, {
      props: { source: 'http://localhost:4566/index.m3u8' },
    })
    await flushPromises()

    expect(hlsInstance.loadSource).toHaveBeenCalledWith('http://localhost:4566/index.m3u8')
    expect(hlsInstance.attachMedia).toHaveBeenCalled()
  })

  it('emits error when hls.js reports a fatal error', async () => {
    hlsInstance.on.mockImplementation((_event, handler) => {
      handler('hlsError', {
        type: Hls.ErrorTypes.NETWORK_ERROR,
        details: Hls.ErrorDetails.MANIFEST_LOAD_ERROR,
        fatal: true,
      })
    })

    const wrapper = mount(HlsPlayer, {
      props: { source: 'http://localhost:4566/index.m3u8' },
    })
    await flushPromises()

    expect(wrapper.emitted('error')?.[0]?.[0]).toContain('manifest')
  })

  it('destroys hls instance on unmount', async () => {
    const wrapper = mount(HlsPlayer, {
      props: { source: 'http://localhost:4566/index.m3u8' },
    })
    await flushPromises()

    wrapper.unmount()
    expect(hlsInstance.destroy).toHaveBeenCalled()
  })

  it('reloads when source prop changes', async () => {
    const wrapper = mount(HlsPlayer, {
      props: { source: 'http://localhost:4566/first.m3u8' },
    })
    await flushPromises()

    await wrapper.setProps({ source: 'http://localhost:4566/second.m3u8' })
    await flushPromises()

    expect(hlsInstance.loadSource).toHaveBeenLastCalledWith('http://localhost:4566/second.m3u8')
  })

  it('emits error when HLS is not supported', async () => {
    vi.mocked(Hls.isSupported).mockReturnValue(false)

    const wrapper = mount(HlsPlayer, {
      props: { source: 'http://localhost:4566/index.m3u8' },
      attachTo: document.body,
    })
    await flushPromises()

    const video = wrapper.get('[data-testid="hls-video"]').element as HTMLVideoElement
    vi.spyOn(video, 'canPlayType').mockReturnValue('')

    await wrapper.setProps({ source: null })
    await wrapper.setProps({ source: 'http://localhost:4566/index.m3u8' })
    await flushPromises()

    expect(wrapper.emitted('error')?.[0]?.[0]).toContain('not supported')
    wrapper.unmount()
  })
})
