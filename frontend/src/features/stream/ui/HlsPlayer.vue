<script setup lang="ts">
import Hls from 'hls.js'
import { describeHlsError } from '@/features/stream/utils/hlsErrors'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  source: string | null
}>()

const emit = defineEmits<{
  error: [message: string]
}>()

const videoRef = ref<HTMLVideoElement | null>(null)
let hls: Hls | null = null

function destroyPlayer(): void {
  if (hls) {
    hls.destroy()
    hls = null
  }

  const video = videoRef.value
  if (video) {
    video.removeAttribute('src')
    video.load()
  }
}

function attachSource(url: string): void {
  const video = videoRef.value
  if (!video) {
    return
  }

  destroyPlayer()

  if (Hls.isSupported()) {
    hls = new Hls()
    hls.loadSource(url)
    hls.attachMedia(video)
    hls.on(Hls.Events.ERROR, (_event, data) => {
      const message = describeHlsError(data)
      if (message) {
        emit('error', message)
      }
    })
    return
  }

  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = url
    video.onerror = () => {
      emit('error', 'Playback error: native HLS playback failed. The manifest or media file may be unavailable.')
    }
    return
  }

  emit('error', 'Playback error: HLS playback is not supported in this browser.')
}

onMounted(() => {
  if (props.source) {
    attachSource(props.source)
  }
})

watch(
  () => props.source,
  (url) => {
    if (url) {
      attachSource(url)
    } else {
      destroyPlayer()
    }
  },
)

onBeforeUnmount(destroyPlayer)
</script>

<template>
  <div class="overflow-hidden rounded-xl border border-slate-200 bg-black">
    <video
      ref="videoRef"
      class="aspect-video w-full bg-black"
      controls
      playsinline
      data-testid="hls-video"
    />
  </div>
</template>
