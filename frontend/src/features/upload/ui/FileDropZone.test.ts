import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import FileDropZone from '@/features/upload/ui/FileDropZone.vue'

describe('FileDropZone', () => {
  it('emits select when a file is chosen via input', async () => {
    const wrapper = mount(FileDropZone)
    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })
    const input = wrapper.get('[data-testid="file-input"]').element as HTMLInputElement

    Object.defineProperty(input, 'files', { value: [file] })
    await wrapper.get('[data-testid="file-input"]').trigger('change')

    expect(wrapper.emitted('select')).toEqual([[file]])
  })

  it('emits select when a file is dropped', async () => {
    const wrapper = mount(FileDropZone)
    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })
    const dropZone = wrapper.get('[data-testid="file-drop-zone"]')

    await dropZone.trigger('dragover')
    expect(dropZone.classes()).toContain('border-sky-500')

    await dropZone.trigger('drop', {
      dataTransfer: { files: [file] },
    })

    expect(wrapper.emitted('select')).toEqual([[file]])
    expect(dropZone.classes()).not.toContain('border-sky-500')
  })

  it('clears drag styling on dragleave', async () => {
    const wrapper = mount(FileDropZone)
    const dropZone = wrapper.get('[data-testid="file-drop-zone"]')

    await dropZone.trigger('dragover')
    await dropZone.trigger('dragleave')

    expect(dropZone.classes()).not.toContain('border-sky-500')
  })

  it('opens file picker when browse is clicked', async () => {
    const wrapper = mount(FileDropZone)
    const input = wrapper.get('[data-testid="file-input"]').element as HTMLInputElement
    const clickSpy = vi.spyOn(input, 'click')

    await wrapper.get('[data-testid="browse-button"]').trigger('click')
    expect(clickSpy).toHaveBeenCalled()
  })
})
