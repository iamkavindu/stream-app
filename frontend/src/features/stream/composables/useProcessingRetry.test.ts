import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useProcessingRetry } from './useProcessingRetry'
import { retryProcessing } from '@/features/stream/api/processingRetryApi'
vi.mock('@/features/stream/api/processingRetryApi', () => ({ retryProcessing: vi.fn() }))

afterEach(() => vi.clearAllMocks())
function setup() {
  const changed = vi.fn().mockResolvedValue(undefined)
  let state!: ReturnType<typeof useProcessingRetry>
  const wrapper = mount(defineComponent({ setup() { state = useProcessingRetry(changed); return () => null } }))
  return { state, changed, wrapper }
}
describe('processing retry', () => {
  it('blocks duplicate clicks and refreshes only after acceptance', async () => {
    let complete!: () => void
    vi.mocked(retryProcessing).mockReturnValueOnce(new Promise<void>((resolve) => { complete = resolve }))
    const { state, changed, wrapper } = setup()
    const request = state.retry('video')
    await state.retry('video')
    expect(retryProcessing).toHaveBeenCalledTimes(1)
    expect(state.pending.value.has('video')).toBe(true)
    expect(changed).not.toHaveBeenCalled()
    complete()
    await request
    expect(changed).toHaveBeenCalledWith('video', true)
    expect(state.pending.value.size).toBe(0)
    wrapper.unmount()
  })
  it('shows failure and reconciles an ambiguous response without claiming success', async () => {
    vi.mocked(retryProcessing).mockRejectedValueOnce(new Error('Source missing'))
    const { state, changed, wrapper } = setup()
    await state.retry('video')
    expect(state.errors.value.video).toBe('Source missing')
    expect(changed).toHaveBeenCalledWith('video', false)
    expect(state.pending.value.size).toBe(0)
    wrapper.unmount()
  })
  it('late success after unmount cannot restart refresh/polling', async () => {
    let complete!: () => void
    vi.mocked(retryProcessing).mockReturnValueOnce(new Promise<void>((resolve) => { complete = resolve }))
    const { state, changed, wrapper } = setup()
    const request = state.retry('video')
    wrapper.unmount()
    complete()
    await request
    await flushPromises()
    expect(changed).not.toHaveBeenCalled()
  })
})
