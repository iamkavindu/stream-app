import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { UploadQueueItem } from '@/features/upload/types'
import UploadQueueItemCard from '@/features/upload/ui/UploadQueueItemCard.vue'

function createItem(overrides: Partial<UploadQueueItem> = {}): UploadQueueItem {
  return {
    id: 'item-1',
    file: new File(['video'], 'demo.mp4', { type: 'video/mp4' }),
    phase: 'creating',
    sha256: 'abc',
    uploadProgress: 0,
    result: null,
    error: null,
    failedAtPhase: null,
    ...overrides,
  }
}

describe('UploadQueueItemCard', () => {
  it('shows overall progress while a step is active', () => {
    const wrapper = mount(UploadQueueItemCard, {
      props: { item: createItem({ phase: 'creating' }) },
    })

    expect(wrapper.get('[data-testid="upload-progress-value"]').text()).toBe('30%')
    expect(wrapper.get('[data-testid="queue-step-creating"]').classes()).toContain('border-sky-300')
  })

  it('alerts the user when upload fails', () => {
    const wrapper = mount(UploadQueueItemCard, {
      props: {
        item: createItem({
          phase: 'failed',
          failedAtPhase: 'creating',
          error: 'Video is already uploaded: demo.mp4',
        }),
      },
    })

    expect(wrapper.get('[data-testid="queue-item-error-message"]').text()).toBe(
      'Video is already uploaded: demo.mp4',
    )
    expect(wrapper.get('[data-testid="queue-step-creating"]').classes()).toContain('border-red-300')
    expect(wrapper.find('[data-testid="queue-item-remove"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="queue-item-retry"]').exists()).toBe(true)
  })

  it('emits retry when the retry button is clicked', async () => {
    const wrapper = mount(UploadQueueItemCard, {
      props: {
        item: createItem({
          phase: 'failed',
          failedAtPhase: 'uploading',
          error: 'Upload interrupted',
        }),
      },
    })

    await wrapper.get('[data-testid="queue-item-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([['item-1']])
  })

  it('shows upload id when complete', () => {
    const wrapper = mount(UploadQueueItemCard, {
      props: {
        item: createItem({
          phase: 'complete',
          result: {
            uploadId: '550e8400-e29b-41d4-a716-446655440000',
            signedUrl: 'http://localhost:4566/object',
            fileName: 'demo.mp4',
          },
        }),
      },
    })

    expect(wrapper.get('[data-testid="queue-item-upload-id"]').text()).toBe(
      '550e8400-e29b-41d4-a716-446655440000',
    )
    expect(wrapper.find('[data-testid="queue-item-retry"]').exists()).toBe(false)
  })
})
