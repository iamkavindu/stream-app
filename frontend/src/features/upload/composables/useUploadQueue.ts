import { ref } from 'vue'
import { createSignedUpload } from '@/features/upload/api/videoApi'
import type { UploadQueueItem, UploadStep } from '@/features/upload/types'
import { isMp4File, isRemovablePhase } from '@/features/upload/types'
import { getErrorMessage } from '@/shared/api/apiError'
import { putFileWithProgress } from '@/shared/utils/putWithProgress'
import { sha256Hex } from '@/shared/utils/sha256'

function createQueueItem(file: File): UploadQueueItem {
  return {
    id: crypto.randomUUID(),
    file,
    phase: 'hashing',
    sha256: null,
    uploadProgress: 0,
    result: null,
    error: null,
    failedAtPhase: null,
  }
}

export function useUploadQueue() {
  const items = ref<UploadQueueItem[]>([])

  function getItem(id: string): UploadQueueItem | undefined {
    return items.value.find((entry) => entry.id === id)
  }

  function updateItem(id: string, patch: Partial<UploadQueueItem>): void {
    items.value = items.value.map((item) => (item.id === id ? { ...item, ...patch } : item))
  }

  function failItem(id: string, failedAtPhase: UploadStep, error: unknown): void {
    updateItem(id, {
      phase: 'failed',
      error: getErrorMessage(error),
      failedAtPhase,
    })
  }

  async function runUpload(id: string): Promise<void> {
    const item = getItem(id)
    if (!item) {
      return
    }

    try {
      updateItem(id, { phase: 'hashing', error: null, failedAtPhase: null })
      const digest = await sha256Hex(item.file)
      updateItem(id, { sha256: digest, phase: 'creating' })

      const signed = await createSignedUpload({
        fileName: item.file.name,
        sha256Hex: digest,
      })

      updateItem(id, { phase: 'uploading', uploadProgress: 0 })
      await putFileWithProgress(signed.signedUrl, item.file, (percent) => {
        updateItem(id, { uploadProgress: percent })
      })

      updateItem(id, { phase: 'complete', result: signed, uploadProgress: 100 })
    } catch (error) {
      const failedAtPhase = (getItem(id)?.phase ?? 'hashing') as UploadStep
      failItem(id, failedAtPhase, error)
    }
  }

  function addFile(file: File): string | null {
    if (!isMp4File(file)) {
      return 'Only .mp4 files are supported.'
    }

    const item = createQueueItem(file)
    items.value = [...items.value, item]
    void runUpload(item.id)
    return null
  }

  function removeItem(id: string): void {
    const item = getItem(id)
    if (!item || !isRemovablePhase(item.phase)) {
      return
    }

    items.value = items.value.filter((entry) => entry.id !== id)
  }

  function retryItem(id: string): void {
    const item = getItem(id)
    if (!item || item.phase !== 'failed') {
      return
    }

    updateItem(id, {
      phase: 'hashing',
      sha256: null,
      uploadProgress: 0,
      result: null,
      error: null,
      failedAtPhase: null,
    })
    void runUpload(id)
  }

  return {
    items,
    addFile,
    removeItem,
    retryItem,
  }
}
