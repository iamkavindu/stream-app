import { apiBaseUrl } from '@/shared/config/env'
import { readApiError } from '@/shared/api/apiError'
import type { SignedUrlCreateRequest, SignedUrlCreatedResponse } from '@/features/upload/types'

export async function createSignedUpload(
  request: SignedUrlCreateRequest,
): Promise<SignedUrlCreatedResponse> {
  const response = await fetch(`${apiBaseUrl}/api/v1/videos`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    throw await readApiError(response)
  }

  return (await response.json()) as SignedUrlCreatedResponse
}
