<script setup lang="ts">
import AppTabNav from '@/shared/ui/AppTabNav.vue'
</script>

<template>
  <div class="min-h-screen bg-slate-50 text-slate-900">
    <header class="border-b border-slate-200 bg-white">
      <div class="mx-auto flex max-w-5xl flex-col gap-4 px-6 py-5 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p class="text-xs font-semibold uppercase tracking-wide text-sky-600">stream-app</p>
          <h1 class="text-xl font-semibold text-slate-900">Video platform</h1>
        </div>
        <AppTabNav />
      </div>
    </header>

    <main class="mx-auto max-w-5xl px-6 py-8">
      <RouterView />
    </main>
  </div>
</template>
