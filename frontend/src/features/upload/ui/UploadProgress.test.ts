import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import UploadProgress from '@/features/upload/ui/UploadProgress.vue'

describe('UploadProgress', () => {
  it('shows label and percent', () => {
    const wrapper = mount(UploadProgress, {
      props: { percent: 65, label: 'Uploading to storage' },
    })

    expect(wrapper.text()).toContain('Uploading to storage')
    expect(wrapper.get('[data-testid="upload-progress-value"]').text()).toBe('65%')
  })

  it('sets bar width from percent', () => {
    const wrapper = mount(UploadProgress, {
      props: { percent: 40, label: 'Working' },
    })

    const bar = wrapper.get('[data-testid="upload-progress"]').find('.h-full')
    expect(bar?.attributes('style')).toContain('width: 40%')
  })

  it('uses success variant styling', () => {
    const wrapper = mount(UploadProgress, {
      props: { percent: 100, label: 'Upload complete', variant: 'success' },
    })

    const bar = wrapper.get('[data-testid="upload-progress"]').find('.h-full')
    expect(bar?.classes()).toContain('bg-emerald-600')
  })

  it('uses error variant styling', () => {
    const wrapper = mount(UploadProgress, {
      props: { percent: 0, label: 'Upload failed', variant: 'error' },
    })

    const bar = wrapper.get('[data-testid="upload-progress"]').find('.h-full')
    expect(bar?.classes()).toContain('bg-red-500')
  })
})
