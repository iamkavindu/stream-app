<script setup lang="ts">
import { computed } from 'vue'
import type { UploadQueueItem } from '@/features/upload/types'
import {
  UPLOAD_STEPS,
  UPLOAD_STEP_LABELS,
  computeOverallProgress,
  stepStatus,
  uploadPhaseLabel,
} from '@/features/upload/types'
import UploadProgress from '@/features/upload/ui/UploadProgress.vue'

const props = defineProps<{
  item: UploadQueueItem
}>()

const emit = defineEmits<{
  remove: [id: string]
  retry: [id: string]
}>()

const phaseLabel = computed(() => uploadPhaseLabel(props.item.phase))
const isComplete = computed(() => props.item.phase === 'complete')
const isFailed = computed(() => props.item.phase === 'failed')
const isActive = computed(() => !isComplete.value && !isFailed.value)
const overallProgress = computed(() =>
  computeOverallProgress(props.item.phase, props.item.uploadProgress),
)
const progressVariant = computed(() => {
  if (isComplete.value) {
    return 'success'
  }
  if (isFailed.value) {
    return 'error'
  }
  return 'default'
})

const cardClass = computed(() => {
  if (isComplete.value) {
    return 'border-emerald-200 bg-emerald-50/40'
  }
  if (isFailed.value) {
    return 'border-red-200 bg-red-50/40'
  }
  return 'border-slate-200 bg-white'
})

function stepChipClass(status: ReturnType<typeof stepStatus>): string {
  switch (status) {
    case 'done':
      return 'border-emerald-200 bg-emerald-50 text-emerald-800'
    case 'active':
      return 'border-sky-300 bg-sky-50 text-sky-800'
    case 'failed':
      return 'border-red-300 bg-red-50 text-red-800'
    default:
      return 'border-slate-200 bg-slate-50 text-slate-500'
  }
}
</script>

<template>
  <article
    class="rounded-xl border p-4 shadow-sm"
    :class="cardClass"
    :data-testid="`queue-item-${item.id}`"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1 space-y-3">
        <p class="truncate text-sm font-medium text-slate-900" data-testid="queue-item-name">
          {{ item.file.name }}
        </p>

        <div class="flex flex-wrap gap-2" data-testid="queue-item-steps">
          <span
            v-for="step in UPLOAD_STEPS"
            :key="step"
            class="rounded-full border px-2.5 py-0.5 text-xs font-medium"
            :class="stepChipClass(stepStatus(step, item.phase, item.failedAtPhase))"
            :data-testid="`queue-step-${step}`"
          >
            {{ UPLOAD_STEP_LABELS[step] }}
          </span>
        </div>

        <UploadProgress
          v-if="isActive || isComplete"
          :percent="overallProgress"
          :label="phaseLabel"
          :variant="progressVariant"
        />

        <div
          v-if="isFailed && item.error"
          class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800"
          role="alert"
          data-testid="queue-item-error"
        >
          <p class="font-medium">Upload failed at {{ UPLOAD_STEP_LABELS[item.failedAtPhase ?? 'hashing'] }}</p>
          <p class="mt-1" data-testid="queue-item-error-message">{{ item.error }}</p>
        </div>

        <dl v-if="item.sha256 || item.result" class="grid gap-1 text-xs">
<!--          <div v-if="item.sha256" class="flex justify-between gap-3">-->
<!--            <dt class="shrink-0 text-slate-500">SHA-256</dt>-->
<!--            <dd class="truncate font-mono text-slate-700" data-testid="queue-item-sha256">-->
<!--              {{ item.sha256 }}-->
<!--            </dd>-->
<!--          </div>-->
          <div v-if="item.result" class="flex justify-between gap-3">
            <dt class="shrink-0 text-slate-500">Upload ID</dt>
            <dd class="truncate font-mono text-slate-700" data-testid="queue-item-upload-id">
              {{ item.result.uploadId }}
            </dd>
          </div>
        </dl>
      </div>

      <div v-if="isComplete || isFailed" class="flex shrink-0 flex-col gap-1">
        <button
          v-if="isFailed"
          type="button"
          class="rounded-md px-2 py-1 text-xs font-medium text-sky-700 hover:bg-white/80"
          data-testid="queue-item-retry"
          @click="emit('retry', item.id)"
        >
          Retry
        </button>
        <button
          type="button"
          class="rounded-md px-2 py-1 text-xs font-medium text-slate-500 hover:bg-white/80 hover:text-slate-700"
          data-testid="queue-item-remove"
          aria-label="Remove from list"
          @click="emit('remove', item.id)"
        >
          Remove
        </button>
      </div>
    </div>
  </article>
</template>
