<script setup lang="ts">
import { computed } from 'vue'
import type { VideoRecord } from '@/features/stream/types'
import {
  isFailed,
  isInProgress,
  isPlayable,
  playerStatusMessage,
  videoStatusBadgeClass,
  videoStatusLabel,
} from '@/features/stream/types'

const props = defineProps<{
  video: VideoRecord
  selected: boolean
}>()

const emit = defineEmits<{
  select: [video: VideoRecord]
}>()

const isSelected = computed(() => props.selected)
const playable = computed(() => isPlayable(props.video.status))
const failed = computed(() => isFailed(props.video.status))
const inProgress = computed(() => isInProgress(props.video.status))

const cardClass = computed(() => {
  if (isSelected.value) {
    if (failed.value) {
      return 'border-red-300 bg-red-50/60 ring-1 ring-red-200'
    }
    return 'border-sky-300 bg-sky-50/60 ring-1 ring-sky-200'
  }
  if (failed.value) {
    return 'border-red-200 bg-red-50/40 hover:border-red-300'
  }
  if (playable.value) {
    return 'border-slate-200 bg-white hover:border-sky-200 hover:bg-sky-50/30'
  }
  return 'border-slate-200 bg-slate-50/60 opacity-90 hover:opacity-100'
})

const statusHint = computed(() => {
  if (failed.value) {
    return playerStatusMessage(props.video.status)
  }
  if (inProgress.value) {
    return 'Processing — the library refreshes automatically.'
  }
  return null
})
</script>

<template>
  <button
    type="button"
    class="w-full rounded-xl border p-4 text-left transition"
    :class="cardClass"
    :data-testid="`playlist-item-${video.uploadId}`"
    @click="emit('select', video)"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1">
        <p class="truncate font-medium text-slate-900">{{ video.fileName }}</p>
        <p class="mt-1 truncate font-mono text-xs text-slate-400">{{ video.uploadId }}</p>
        <p
          v-if="statusHint"
          class="mt-2 text-xs leading-relaxed"
          :class="failed ? 'text-red-700' : 'text-slate-500'"
          :data-testid="failed ? 'playlist-item-failed-hint' : 'playlist-item-progress-hint'"
        >
          {{ statusHint }}
        </p>
      </div>
      <span
        class="shrink-0 rounded-full border px-2 py-0.5 text-xs font-medium"
        :class="videoStatusBadgeClass(video.status)"
        :data-testid="`playlist-item-status-${video.status}`"
      >
        {{ videoStatusLabel(video.status) }}
      </span>
    </div>
  </button>
</template>
