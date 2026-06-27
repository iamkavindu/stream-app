<script setup lang="ts">
import { ref } from 'vue'
import { useStreamPlayback } from '@/features/stream/composables/useStreamPlayback'
import HlsPlayer from '@/features/stream/ui/HlsPlayer.vue'
import VideoPlaylistItem from '@/features/stream/ui/VideoPlaylistItem.vue'

const {
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
} = useStreamPlayback()

const playbackError = ref<string | null>(null)

function onSelect(video: Parameters<typeof selectVideo>[0]): void {
  playbackError.value = null
  void selectVideo(video)
}

function onPlaybackError(message: string): void {
  playbackError.value = message
}
</script>

<template>
  <section class="mx-auto max-w-6xl space-y-6">
    <header>
      <h2 class="text-2xl font-semibold text-slate-900">Stream</h2>
      <p class="mt-1 text-sm text-slate-600">
        Choose a ready video from the library. Playback uses HLS via the signed manifest URL from
        the API.
      </p>
    </header>

    <div class="grid gap-6 lg:grid-cols-2 lg:items-start">
      <div class="space-y-3">
        <div class="flex items-center justify-between gap-3">
          <div>
            <h3 class="text-sm font-semibold uppercase tracking-wide text-slate-500">Library</h3>
            <p
              v-if="shouldPoll"
              class="mt-0.5 text-xs text-amber-700"
              data-testid="list-polling-indicator"
            >
              Auto-refreshing while videos are processing…
            </p>
          </div>
          <button
            type="button"
            class="text-xs font-medium text-sky-600 hover:text-sky-700 disabled:text-slate-400"
            :disabled="listLoading"
            data-testid="refresh-videos"
            @click="() => loadVideos()"
          >
            Refresh
          </button>
        </div>

        <p v-if="listLoading" class="text-sm text-slate-500" data-testid="list-loading">
          Loading videos…
        </p>

        <div
          v-else-if="listError"
          class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800"
          role="alert"
          data-testid="list-error"
        >
          <p class="font-medium">Could not load video library</p>
          <p class="mt-1">{{ listError }}</p>
        </div>

        <p
          v-else-if="videos.length === 0"
          class="rounded-xl border border-dashed border-slate-200 bg-white p-8 text-center text-sm text-slate-500"
          data-testid="playlist-empty"
        >
          No videos yet. Upload one from the Upload tab.
        </p>

        <div v-else class="flex max-h-[32rem] flex-col gap-3 overflow-y-auto pr-1">
          <VideoPlaylistItem
            v-for="video in videos"
            :key="video.uploadId"
            :video="video"
            :selected="selectedUploadId === video.uploadId"
            @select="onSelect"
          />
        </div>
      </div>

      <div class="space-y-3">
        <h3 class="text-sm font-semibold uppercase tracking-wide text-slate-500">Player</h3>

        <div
          v-if="!selectedUploadId"
          class="flex aspect-video items-center justify-center rounded-xl border border-dashed border-slate-200 bg-white p-8 text-center text-sm text-slate-500"
          data-testid="player-placeholder"
        >
          Select a video to start playback.
        </div>

        <template v-else>
          <p v-if="playerLoading" class="text-sm text-slate-500" data-testid="player-loading">
            Loading stream URL from API…
          </p>

          <div
            v-else-if="playerError"
            class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800"
            role="alert"
            data-testid="player-error"
          >
            <p class="font-medium">Cannot start playback</p>
            <p class="mt-1">{{ playerError }}</p>
          </div>

          <template v-else-if="manifestUrl">
            <HlsPlayer :source="manifestUrl" @error="onPlaybackError" />
            <div
              v-if="playbackError"
              class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800"
              role="alert"
              data-testid="playback-error"
            >
              <p class="font-medium">Stream playback failed</p>
              <p class="mt-1">{{ playbackError }}</p>
            </div>
          </template>
        </template>
      </div>
    </div>
  </section>
</template>
