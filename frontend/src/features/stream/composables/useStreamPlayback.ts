import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getSignedStreamUrl, listVideos } from '@/features/stream/api/streamApi'
import type { VideoRecord } from '@/features/stream/types'
import {
  isInProgress,
  isPlayable,
  playerStatusMessage,
} from '@/features/stream/types'
import { ApiError } from '@/shared/api/apiError'
import { getErrorMessage } from '@/shared/api/apiError'

export const STREAM_POLL_INTERVAL_MS = 4000

export function useStreamPlayback() {
  const videos = ref<VideoRecord[]>([])
  const listLoading = ref(false)
  const listError = ref<string | null>(null)

  const selectedUploadId = ref<string | null>(null)
  const manifestUrl = ref<string | null>(null)
  const playerLoading = ref(false)
  const playerError = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setInterval> | null = null

  const shouldPoll = computed(() => videos.value.some((video) => isInProgress(video.status)))

  function stopPolling(): void {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function syncPolling(): void {
    if (shouldPoll.value) {
      if (!pollTimer) {
        pollTimer = setInterval(() => {
          void loadVideos({ silent: true })
        }, STREAM_POLL_INTERVAL_MS)
      }
    } else {
      stopPolling()
    }
  }

  async function loadVideos(options: { silent?: boolean } = {}): Promise<void> {
    if (!options.silent) {
      listLoading.value = true
    }
    listError.value = null

    try {
      videos.value = await listVideos()
      syncPolling()

      const selected = videos.value.find((video) => video.uploadId === selectedUploadId.value)
      if (selected && isPlayable(selected.status) && !manifestUrl.value && !playerLoading.value) {
        await selectVideo(selected)
      }
    } catch (error) {
      listError.value = getErrorMessage(error)
    } finally {
      if (!options.silent) {
        listLoading.value = false
      }
    }
  }

  async function selectVideo(video: VideoRecord): Promise<void> {
    selectedUploadId.value = video.uploadId
    manifestUrl.value = null
    playerError.value = null

    if (!isPlayable(video.status)) {
      playerError.value = playerStatusMessage(video.status)
      return
    }

    playerLoading.value = true

    try {
      const response = await getSignedStreamUrl(video.uploadId)
      manifestUrl.value = response.signedUrl
    } catch (error) {
      playerError.value = formatStreamApiError(error)
    } finally {
      playerLoading.value = false
    }
  }

  onMounted(() => {
    void loadVideos()
  })

  onUnmounted(stopPolling)

  return {
    videos,
    listLoading,
    listError,
    selectedUploadId,
    manifestUrl,
    playerLoading,
    playerError,
    shouldPoll,
    loadVideos,
    selectVideo,
  }
}

function formatStreamApiError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return `Stream not ready: ${error.message}`
    }
    if (error.status === 404) {
      return `Video not found: ${error.message}`
    }
    return `Could not load stream URL: ${error.message}`
  }
  return getErrorMessage(error)
}
