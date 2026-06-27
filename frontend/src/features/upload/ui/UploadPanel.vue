<script setup lang="ts">
import { ref } from 'vue'
import { useUploadQueue } from '@/features/upload/composables/useUploadQueue'
import FileDropZone from '@/features/upload/ui/FileDropZone.vue'
import UploadQueueItemCard from '@/features/upload/ui/UploadQueueItemCard.vue'

const { items, addFile, removeItem, retryItem } = useUploadQueue()
const selectionError = ref<string | null>(null)

function onFileSelected(file: File): void {
  selectionError.value = addFile(file)
}
</script>

<template>
  <section class="mx-auto max-w-6xl space-y-6">
    <header>
      <h2 class="text-2xl font-semibold text-slate-900">Upload video</h2>
      <p class="mt-1 text-sm text-slate-600">
        Add MP4 files anytime. Each file is hashed, receives a signed URL, and uploads directly to
        storage while you queue more.
      </p>
    </header>

    <div class="grid gap-6 lg:grid-cols-2 lg:items-start">
      <div class="space-y-3">
        <FileDropZone @select="onFileSelected" />

        <p v-if="selectionError" data-testid="selection-error" class="text-sm text-red-600">
          {{ selectionError }}
        </p>
      </div>

      <div class="space-y-3">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold uppercase tracking-wide text-slate-500">Upload queue</h3>
          <span class="text-xs text-slate-400" data-testid="queue-count">{{ items.length }}</span>
        </div>

        <p
          v-if="items.length === 0"
          class="rounded-xl border border-dashed border-slate-200 bg-white p-8 text-center text-sm text-slate-500"
          data-testid="queue-empty"
        >
          Selected files will appear here.
        </p>

        <div v-else class="flex max-h-[32rem] flex-col gap-3 overflow-y-auto pr-1">
          <UploadQueueItemCard
            v-for="item in items"
            :key="item.id"
            :item="item"
            @remove="removeItem"
            @retry="retryItem"
          />
        </div>
      </div>
    </div>
  </section>
</template>
