import { afterEach, describe, expect, it, vi } from 'vitest'
import { putFileWithProgress } from '@/shared/utils/putWithProgress'

type XhrListener = (event?: ProgressEvent) => void

class MockXMLHttpRequest {
  static instances: MockXMLHttpRequest[] = []

  open = vi.fn()
  setRequestHeader = vi.fn()
  send = vi.fn()
  status = 200
  upload = {
    addEventListener: vi.fn(),
  }
  private listeners = new Map<string, XhrListener[]>()

  addEventListener(event: string, listener: XhrListener): void {
    const handlers = this.listeners.get(event) ?? []
    handlers.push(listener)
    this.listeners.set(event, handlers)
  }

  emit(event: string, payload?: ProgressEvent): void {
    for (const listener of this.listeners.get(event) ?? []) {
      listener(payload)
    }
  }

  constructor() {
    MockXMLHttpRequest.instances.push(this)
  }
}

describe('putFileWithProgress', () => {
  afterEach(() => {
    MockXMLHttpRequest.instances = []
    vi.unstubAllGlobals()
  })

  it('uploads file and reports progress', async () => {
    vi.stubGlobal('XMLHttpRequest', MockXMLHttpRequest)

    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })
    const progress: number[] = []

    const promise = putFileWithProgress('https://s3.example/upload', file, (percent) => {
      progress.push(percent)
    })

    const xhr = MockXMLHttpRequest.instances[0]
    expect(xhr).toBeDefined()
    expect(xhr?.open).toHaveBeenCalledWith('PUT', 'https://s3.example/upload')
    expect(xhr?.setRequestHeader).toHaveBeenCalledWith('Content-Type', 'video/mp4')

    const progressHandler = xhr?.upload.addEventListener.mock.calls.find(
      ([event]) => event === 'progress',
    )?.[1] as XhrListener | undefined

    progressHandler?.({
      lengthComputable: true,
      loaded: 50,
      total: 100,
    } as ProgressEvent)

    xhr?.emit('load')
    await promise

    expect(progress).toEqual([50])
  })

  it('rejects when upload returns non-success status', async () => {
    vi.stubGlobal('XMLHttpRequest', MockXMLHttpRequest)

    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })
    const promise = putFileWithProgress('https://s3.example/upload', file, () => {})

    const xhr = MockXMLHttpRequest.instances[0]
    xhr!.status = 500
    xhr?.emit('load')

    await expect(promise).rejects.toThrow('S3 upload failed with status 500')
  })
})
