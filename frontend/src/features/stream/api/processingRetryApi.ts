import { apiBaseUrl } from '@/shared/config/env'
import { readApiError } from '@/shared/api/apiError'

export async function retryProcessing(uploadId: string): Promise<void> {
  const response = await fetch(`${apiBaseUrl}/api/v1/videos/${encodeURIComponent(uploadId)}/processing-retries`, {
    method: 'POST',
  })
  if (!response.ok) throw await readApiError(response)
}
