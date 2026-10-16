import { onUnmounted, ref } from 'vue'
import { retryProcessing } from '@/features/stream/api/processingRetryApi'
import { getErrorMessage } from '@/shared/api/apiError'

export function useProcessingRetry(onChanged: (id: string, accepted: boolean) => Promise<void>) {
  const pending = ref(new Set<string>())
  const errors = ref<Record<string, string>>({})
  let disposed = false
  onUnmounted(() => { disposed = true })

  async function retry(id: string): Promise<void> {
    if (disposed || pending.value.has(id)) return
    pending.value.add(id)
    delete errors.value[id]
    let accepted = false
    try {
      await retryProcessing(id)
      accepted = true
    } catch (error) {
      if (!disposed) errors.value[id] = getErrorMessage(error)
    } finally {
      if (!disposed) {
        // Also reconcile an ambiguous/lost HTTP response, without assuming acceptance.
        try { await onChanged(id, accepted) }
        finally { if (!disposed) pending.value.delete(id) }
      }
    }
  }
  return { retry, pending, errors }
}
