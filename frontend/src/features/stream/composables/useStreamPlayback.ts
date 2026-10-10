import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getSignedStreamUrl, listVideos } from '@/features/stream/api/streamApi'
import type { VideoRecord } from '@/features/stream/types'
import { isInProgress, isPlayable, playerStatusMessage } from '@/features/stream/types'
import { ApiError } from '@/shared/api/apiError'
import { getErrorMessage } from '@/shared/api/apiError'

export const STREAM_POLL_INTERVAL_MS = 4000
export const STREAM_POLL_MAX_INTERVAL_MS = 32000

export function useStreamPlayback() {
  const videos = ref<VideoRecord[]>([])
  const listLoading = ref(false)
  const listError = ref<string | null>(null)

  const selectedUploadId = ref<string | null>(null)
  const manifestUrl = ref<string | null>(null)
  const playerLoading = ref(false)
  const playerError = ref<string | null>(null)

  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let disposed = false
  let selectionVersion = 0
  let consecutiveListFailures = 0
  let listRequest: Promise<void> | null = null

  const shouldPoll = computed(() => videos.value.some((video) => isInProgress(video.status)))

  function stopPolling(): void {
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function schedulePolling(): void {
    stopPolling()
    if (disposed || !shouldPoll.value) return
    const delay = Math.min(
      STREAM_POLL_INTERVAL_MS * 2 ** consecutiveListFailures,
      STREAM_POLL_MAX_INTERVAL_MS,
    )
    pollTimer = setTimeout(() => {
      pollTimer = null
      void loadVideos({ silent: true })
    }, delay)
  }

  function clearSelection(): void {
    selectionVersion++
    selectedUploadId.value = null
    manifestUrl.value = null
    playerError.value = null
    playerLoading.value = false
  }

  function loadVideos(options: { silent?: boolean } = {}): Promise<void> {
    if (disposed) return Promise.resolve()
    // Refresh and polling share one request; slow responses cannot overlap the next poll.
    if (listRequest) return listRequest
    stopPolling()
    if (!options.silent) listLoading.value = true
    listError.value = null

    listRequest = (async () => {
      try {
        const response = await listVideos()
        if (disposed) return
        const previousSelected = videos.value.find(
          (video) => video.uploadId === selectedUploadId.value,
        )
        videos.value = response
        consecutiveListFailures = 0

        if (selectedUploadId.value !== null) {
          const selected = response.find((video) => video.uploadId === selectedUploadId.value)
          if (!selected) {
            clearSelection()
          } else if (!isPlayable(selected.status)) {
            void selectVideo(selected)
          } else if (
            !manifestUrl.value &&
            !playerLoading.value &&
            (!playerError.value || (previousSelected && !isPlayable(previousSelected.status)))
          ) {
            void selectVideo(selected)
          }
        }
      } catch (error) {
        if (disposed) return
        listError.value = getErrorMessage(error)
        consecutiveListFailures = Math.min(consecutiveListFailures + 1, 3)
      } finally {
        listRequest = null
        if (!disposed) {
          listLoading.value = false
          schedulePolling()
        }
      }
    })()
    return listRequest
  }

  async function selectVideo(video: VideoRecord): Promise<void> {
    if (disposed) return
    const version = ++selectionVersion
    selectedUploadId.value = video.uploadId
    manifestUrl.value = null
    playerError.value = null
    playerLoading.value = false

    if (!isPlayable(video.status)) {
      playerError.value = playerStatusMessage(video.status)
      return
    }

    playerLoading.value = true
    try {
      const response = await getSignedStreamUrl(video.uploadId)
      if (!disposed && version === selectionVersion) manifestUrl.value = response.signedUrl
    } catch (error) {
      if (!disposed && version === selectionVersion) playerError.value = formatStreamApiError(error)
    } finally {
      if (!disposed && version === selectionVersion) playerLoading.value = false
    }
  }

  onMounted(() => {
    void loadVideos()
  })
  onUnmounted(() => {
    disposed = true
    selectionVersion++
    stopPolling()
  })

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
