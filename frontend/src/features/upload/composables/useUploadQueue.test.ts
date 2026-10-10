import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/apiError'
import { useUploadQueue } from '@/features/upload/composables/useUploadQueue'

vi.mock('@/features/upload/api/videoApi', () => ({
  createSignedUpload: vi.fn(),
  retryUpload: vi.fn(),
}))

vi.mock('@/shared/utils/sha256', () => ({
  sha256Hex: vi.fn(),
}))

vi.mock('@/shared/utils/putWithProgress', () => ({
  putFileWithProgress: vi.fn(),
}))

import { createSignedUpload, retryUpload } from '@/features/upload/api/videoApi'
import { sha256Hex } from '@/shared/utils/sha256'
import { putFileWithProgress } from '@/shared/utils/putWithProgress'

describe('useUploadQueue', () => {
  beforeEach(() => {
    vi.resetAllMocks()
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

  it('retains registration before a failed PUT and renews the same upload without hashing again', async () => {
    const signed = { uploadId: 'same-id', signedUrl: 'expired-url', fileName: 'demo.mp4' }
    vi.mocked(sha256Hex).mockResolvedValue('digest')
    vi.mocked(createSignedUpload).mockResolvedValue(signed)
    vi.mocked(putFileWithProgress).mockRejectedValueOnce(new Error('PUT lost')).mockResolvedValueOnce()
    vi.mocked(retryUpload).mockResolvedValue({ ...signed, signedUrl: 'fresh-url', sourceReceived: false })
    const queue = useUploadQueue()
    queue.addFile(new File(['video'], 'demo.mp4'))
    await vi.waitFor(() => expect(queue.items.value[0]?.phase).toBe('failed'))
    expect(queue.items.value[0]?.result).toEqual(signed)

    queue.retryItem(queue.items.value[0]!.id)
    await vi.waitFor(() => expect(queue.items.value[0]?.phase).toBe('complete'))
    expect(createSignedUpload).toHaveBeenCalledTimes(1)
    expect(sha256Hex).toHaveBeenCalledTimes(1)
    expect(retryUpload).toHaveBeenCalledWith('same-id')
    expect(vi.mocked(putFileWithProgress).mock.calls[1]?.[0]).toBe('fresh-url')
    expect(queue.items.value[0]?.result?.uploadId).toBe('same-id')
  })

  it('reconciles an ambiguous PUT without uploading again when the source was received', async () => {
    const signed = { uploadId: 'received-id', signedUrl: 'original-url', fileName: 'demo.mp4' }
    vi.mocked(sha256Hex).mockResolvedValue('digest')
    vi.mocked(createSignedUpload).mockResolvedValue(signed)
    vi.mocked(putFileWithProgress).mockRejectedValue(new Error('PUT response lost'))
    vi.mocked(retryUpload).mockResolvedValue({ ...signed, signedUrl: null, sourceReceived: true })
    const queue = useUploadQueue()
    queue.addFile(new File(['video'], 'demo.mp4'))
    await vi.waitFor(() => expect(queue.items.value[0]?.phase).toBe('failed'))
    queue.retryItem(queue.items.value[0]!.id)
    await vi.waitFor(() => expect(queue.items.value[0]?.phase).toBe('complete'))
    expect(createSignedUpload).toHaveBeenCalledTimes(1)
    expect(putFileWithProgress).toHaveBeenCalledTimes(1)
    expect(queue.items.value[0]?.uploadProgress).toBe(100)
  })

  it('retains identity after renewal errors and prevents overlapping retries', async () => {
    const signed = { uploadId: 'retained-id', signedUrl: 'original-url', fileName: 'demo.mp4' }
    vi.mocked(sha256Hex).mockResolvedValue('digest')
    vi.mocked(createSignedUpload).mockResolvedValue(signed)
    vi.mocked(putFileWithProgress).mockRejectedValue(new Error('PUT failed'))
    vi.mocked(retryUpload).mockRejectedValueOnce(new ApiError('Upload cannot be retried'))
      .mockResolvedValueOnce({ ...signed, signedUrl: null, sourceReceived: true })
    const queue = useUploadQueue()
    queue.addFile(new File(['video'], 'demo.mp4'))
    await vi.waitFor(() => expect(queue.items.value[0]?.phase).toBe('failed'))
    const id = queue.items.value[0]!.id
    queue.retryItem(id)
    queue.retryItem(id)
    await vi.waitFor(() => expect(queue.items.value[0]?.error).toBe('Upload cannot be retried'))
    expect(retryUpload).toHaveBeenCalledTimes(1)
    expect(queue.items.value[0]?.failedAtPhase).toBe('creating')
    expect(queue.items.value[0]?.result).toEqual(signed)
    queue.retryItem(id)
    await vi.waitFor(() => expect(queue.items.value[0]?.phase).toBe('complete'))
    expect(createSignedUpload).toHaveBeenCalledTimes(1)
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
