export const VIDEO_STATUSES = [
  'AWAITING_UPLOAD',
  'TRANSCODING_IN_PROGRESS',
  'PLAY_READY',
  'FAILED',
] as const

export type VideoStatus = (typeof VIDEO_STATUSES)[number]

export interface VideoRecord {
  uploadId: string
  status: VideoStatus
  fileName: string
  createdAt: string
  updatedAt: string
}

export interface SignedGetUrlResponse {
  uploadId: string
  objectKey: string
  signedUrl: string
}

export function isPlayable(status: VideoStatus): boolean {
  return status === 'PLAY_READY'
}

export function isInProgress(status: VideoStatus): boolean {
  return status === 'AWAITING_UPLOAD' || status === 'TRANSCODING_IN_PROGRESS'
}

export function isTerminal(status: VideoStatus): boolean {
  return status === 'PLAY_READY' || status === 'FAILED'
}

export function isFailed(status: VideoStatus): boolean {
  return status === 'FAILED'
}

export function playerStatusMessage(status: VideoStatus): string {
  switch (status) {
    case 'AWAITING_UPLOAD':
      return 'Upload has not finished yet. The library refreshes automatically while videos are processing.'
    case 'TRANSCODING_IN_PROGRESS':
      return 'This video is still transcoding. Wait for the status badge to show Ready, or select it again after the list updates.'
    case 'FAILED':
      return 'Transcoding failed for this video and it cannot be played. Remove the source file and upload again, or click Refresh to confirm the status.'
    default:
      return 'This video is not ready to stream yet.'
  }
}

export function videoStatusLabel(status: VideoStatus): string {
  switch (status) {
    case 'AWAITING_UPLOAD':
      return 'Awaiting upload'
    case 'TRANSCODING_IN_PROGRESS':
      return 'Transcoding'
    case 'PLAY_READY':
      return 'Ready'
    case 'FAILED':
      return 'Failed'
  }
}

export function videoStatusBadgeClass(status: VideoStatus): string {
  switch (status) {
    case 'PLAY_READY':
      return 'border-emerald-200 bg-emerald-50 text-emerald-800'
    case 'TRANSCODING_IN_PROGRESS':
      return 'border-amber-200 bg-amber-50 text-amber-800'
    case 'FAILED':
      return 'border-red-200 bg-red-50 text-red-800'
    default:
      return 'border-slate-200 bg-slate-50 text-slate-600'
  }
}
