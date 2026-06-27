import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import type { UploadQueueItem } from '@/features/upload/types'
import UploadPanel from '@/features/upload/ui/UploadPanel.vue'

const items = ref<UploadQueueItem[]>([])
const addFile = vi.fn((file: File) => {
  items.value = [
    ...items.value,
    {
      id: 'item-1',
      file,
      phase: 'hashing',
      sha256: null,
      uploadProgress: 0,
      result: null,
      error: null,
      failedAtPhase: null,
    },
  ]
  return null
})
const removeItem = vi.fn()
const retryItem = vi.fn()

vi.mock('@/features/upload/composables/useUploadQueue', () => ({
  useUploadQueue: () => ({
    items,
    addFile,
    removeItem,
    retryItem,
  }),
}))

const FileDropZoneStub = defineComponent({
  emits: ['select'],
  template: `<button data-testid="stub-select" @click="$emit('select', file)">select</button>`,
  setup() {
    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })
    return { file }
  },
})

describe('UploadPanel', () => {
  it('always shows the drop zone', () => {
    items.value = [
      {
        id: 'item-1',
        file: new File(['video'], 'demo.mp4', { type: 'video/mp4' }),
        phase: 'uploading',
        sha256: 'abc',
        uploadProgress: 40,
        result: null,
        error: null,
        failedAtPhase: null,
      },
    ]

    const wrapper = mount(UploadPanel, {
      global: {
        stubs: {
          FileDropZone: FileDropZoneStub,
          UploadQueueItemCard: true,
        },
      },
    })

    expect(wrapper.find('[data-testid="stub-select"]').exists()).toBe(true)
  })

  it('adds selected files to the queue list', async () => {
    items.value = []

    const wrapper = mount(UploadPanel, {
      global: {
        stubs: {
          FileDropZone: FileDropZoneStub,
          UploadQueueItemCard: true,
        },
      },
    })

    await wrapper.find('[data-testid="stub-select"]').trigger('click')
    expect(addFile).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="queue-count"]').text()).toBe('1')
  })
})
