import { apiBaseUrl } from '@/shared/config/env'
import { readApiError } from '@/shared/api/apiError'
import type { SignedGetUrlResponse, VideoRecord } from '@/features/stream/types'

export async function listVideos(): Promise<VideoRecord[]> {
  const response = await fetch(`${apiBaseUrl}/api/v1/videos`)

  if (!response.ok) {
    throw await readApiError(response)
  }

  return (await response.json()) as VideoRecord[]
}

export async function getSignedStreamUrl(uploadId: string): Promise<SignedGetUrlResponse> {
  const response = await fetch(`${apiBaseUrl}/api/v1/videos/${uploadId}/signed-url`)

  if (!response.ok) {
    throw await readApiError(response)
  }

  return (await response.json()) as SignedGetUrlResponse
}
