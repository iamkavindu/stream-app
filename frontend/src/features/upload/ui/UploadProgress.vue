<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    percent: number
    label: string
    variant?: 'default' | 'success' | 'error'
  }>(),
  {
    variant: 'default',
  },
)

const barClass = computed(() => {
  switch (props.variant) {
    case 'success':
      return 'bg-emerald-600'
    case 'error':
      return 'bg-red-500'
    default:
      return 'bg-sky-600'
  }
})
</script>

<template>
  <div data-testid="upload-progress" class="space-y-2">
    <div class="flex items-center justify-between text-sm text-slate-600">
      <span>{{ label }}</span>
      <span data-testid="upload-progress-value">{{ percent }}%</span>
    </div>
    <div class="h-2 overflow-hidden rounded-full bg-slate-200">
      <div
        class="h-full rounded-full transition-all duration-200"
        :class="barClass"
        :style="{ width: `${percent}%` }"
      />
    </div>
  </div>
</template>
