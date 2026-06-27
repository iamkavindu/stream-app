import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/apiError'
import { useUploadQueue } from '@/features/upload/composables/useUploadQueue'

vi.mock('@/features/upload/api/videoApi', () => ({
  createSignedUpload: vi.fn(),
}))

vi.mock('@/shared/utils/sha256', () => ({
  sha256Hex: vi.fn(),
}))

vi.mock('@/shared/utils/putWithProgress', () => ({
  putFileWithProgress: vi.fn(),
}))

import { createSignedUpload } from '@/features/upload/api/videoApi'
import { sha256Hex } from '@/shared/utils/sha256'
import { putFileWithProgress } from '@/shared/utils/putWithProgress'

describe('useUploadQueue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('rejects non-mp4 file selection', () => {
    const queue = useUploadQueue()
    const error = queue.addFile(new File(['x'], 'clip.mov', { type: 'video/quicktime' }))

    expect(error).toBe('Only .mp4 files are supported.')
    expect(queue.items.value).toHaveLength(0)
  })

  it('queues a file and runs the upload pipeline', async () => {
    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })
    const signed = {
      uploadId: '550e8400-e29b-41d4-a716-446655440000',
      signedUrl: 'http://localhost:4566/streamapp-uploads/object',
      fileName: 'demo.mp4',
    }

    vi.mocked(sha256Hex).mockResolvedValue('deadbeef')
    vi.mocked(createSignedUpload).mockResolvedValue(signed)
    vi.mocked(putFileWithProgress).mockImplementation(async (_url, _file, onProgress) => {
      onProgress(100)
    })

    const queue = useUploadQueue()
    expect(queue.addFile(file)).toBeNull()
    expect(queue.items.value).toHaveLength(1)

    await vi.waitFor(() => {
      expect(queue.items.value[0]?.phase).toBe('complete')
    })

    expect(sha256Hex).toHaveBeenCalledWith(file)
    expect(createSignedUpload).toHaveBeenCalledWith({
      fileName: 'demo.mp4',
      sha256Hex: 'deadbeef',
    })
    expect(queue.items.value[0]?.result).toEqual(signed)
  })

  it('marks queue item failed with backend problem detail', async () => {
    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })

    vi.mocked(sha256Hex).mockResolvedValue('deadbeef')
    vi.mocked(createSignedUpload).mockRejectedValue(
      new ApiError('Video is already uploaded: demo.mp4', {
        title: 'Duplicate Video Upload',
        detail: 'Video is already uploaded: demo.mp4',
        status: 409,
      }, 409),
    )

    const queue = useUploadQueue()
    queue.addFile(file)

    await vi.waitFor(() => {
      expect(queue.items.value[0]?.phase).toBe('failed')
    })

    expect(queue.items.value[0]?.error).toBe('Video is already uploaded: demo.mp4')
    expect(queue.items.value[0]?.failedAtPhase).toBe('creating')
  })

  it('retries a failed upload from hashing', async () => {
    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })

    vi.mocked(sha256Hex).mockResolvedValue('deadbeef')
    vi.mocked(createSignedUpload)
      .mockRejectedValueOnce(new ApiError('Network error'))
      .mockResolvedValueOnce({
        uploadId: 'id-retry',
        signedUrl: 'http://localhost:4566/object',
        fileName: 'demo.mp4',
      })
    vi.mocked(putFileWithProgress).mockImplementation(async (_url, _file, onProgress) => {
      onProgress(100)
    })

    const queue = useUploadQueue()
    queue.addFile(file)

    await vi.waitFor(() => {
      expect(queue.items.value[0]?.phase).toBe('failed')
    })

    const id = queue.items.value[0]!.id
    queue.retryItem(id)

    await vi.waitFor(() => {
      expect(queue.items.value[0]?.phase).toBe('complete')
    })

    expect(createSignedUpload).toHaveBeenCalledTimes(2)
    expect(sha256Hex).toHaveBeenCalledTimes(2)
  })

  it('removes completed and failed items from the queue', async () => {
    const file = new File(['video'], 'demo.mp4', { type: 'video/mp4' })

    vi.mocked(sha256Hex).mockResolvedValue('deadbeef')
    vi.mocked(createSignedUpload).mockRejectedValue(new ApiError('Duplicate upload'))

    const queue = useUploadQueue()
    queue.addFile(file)

    await vi.waitFor(() => {
      expect(queue.items.value[0]?.phase).toBe('failed')
    })

    const id = queue.items.value[0]!.id
    queue.removeItem(id)
    expect(queue.items.value).toHaveLength(0)
  })
})
