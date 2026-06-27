<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{
  select: [file: File]
}>()

const isDragging = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

function emitFile(file: File | undefined): void {
  if (file) {
    emit('select', file)
  }
}

function onInputChange(event: Event): void {
  const input = event.target as HTMLInputElement
  emitFile(input.files?.[0])
  input.value = ''
}

function onDrop(event: DragEvent): void {
  isDragging.value = false
  event.preventDefault()
  emitFile(event.dataTransfer?.files[0])
}

function onDragOver(event: DragEvent): void {
  event.preventDefault()
  isDragging.value = true
}

function onDragLeave(): void {
  isDragging.value = false
}

function openPicker(): void {
  inputRef.value?.click()
}
</script>

<template>
  <div
    data-testid="file-drop-zone"
    class="rounded-xl border-2 border-dashed p-10 text-center transition-colors"
    :class="
      isDragging
        ? 'border-sky-500 bg-sky-50'
        : 'border-slate-300 bg-white hover:border-slate-400'
    "
    @drop="onDrop"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
  >
    <input
      ref="inputRef"
      data-testid="file-input"
      type="file"
      accept=".mp4,video/mp4"
      class="hidden"
      @change="onInputChange"
    />

    <p class="text-lg font-medium text-slate-800">Drop an MP4 here</p>
    <p class="mt-1 text-sm text-slate-500">or browse to add another file</p>

    <button
      type="button"
      data-testid="browse-button"
      class="mt-6 rounded-lg bg-sky-600 px-4 py-2 text-sm font-medium text-white hover:bg-sky-700"
      @click="openPicker"
    >
      Browse files
    </button>
  </div>
</template>
