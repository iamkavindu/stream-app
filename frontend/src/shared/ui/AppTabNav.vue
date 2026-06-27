<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'

const route = useRoute()

const tabs = [
  { name: 'upload', label: 'Upload', to: '/upload' },
  { name: 'stream', label: 'Stream', to: '/stream' },
] as const

function isActive(path: string): boolean {
  return route.path === path
}
</script>

<template>
  <nav class="flex gap-1 rounded-lg bg-slate-100 p-1" aria-label="Main navigation">
    <RouterLink
      v-for="tab in tabs"
      :key="tab.name"
      :to="tab.to"
      class="rounded-md px-4 py-2 text-sm font-medium transition-colors"
      :class="
        isActive(tab.to)
          ? 'bg-white text-slate-900 shadow-sm'
          : 'text-slate-600 hover:text-slate-900'
      "
      :data-testid="`tab-${tab.name}`"
    >
      {{ tab.label }}
    </RouterLink>
  </nav>
</template>
