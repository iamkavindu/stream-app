import Hls from 'hls.js'

export function describeHlsError(data: {
  type: string
  details: string
  fatal: boolean
}): string | null {
  if (!data.fatal) {
    return null
  }

  if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
    if (
      data.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR ||
      data.details === Hls.ErrorDetails.MANIFEST_LOAD_TIMEOUT ||
      data.details === Hls.ErrorDetails.MANIFEST_PARSING_ERROR
    ) {
      return 'Playback error: could not load the HLS manifest. The signed URL may have expired or the stream files are missing.'
    }
    if (
      data.details === Hls.ErrorDetails.FRAG_LOAD_ERROR ||
      data.details === Hls.ErrorDetails.FRAG_LOAD_TIMEOUT ||
      data.details === Hls.ErrorDetails.LEVEL_LOAD_ERROR
    ) {
      return 'Playback error: could not load a video segment. Check that the stream bucket allows browser access to media files.'
    }
    return 'Playback error: a network problem prevented the stream from loading.'
  }

  if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
    return 'Playback error: the browser could not decode the stream. The transcoded file may be corrupt.'
  }

  return 'Playback error: the stream could not be played.'
}
