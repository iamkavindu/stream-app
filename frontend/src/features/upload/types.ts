export interface SignedUrlCreateRequest {
  fileName: string
  sha256Hex: string
}

export interface SignedUrlCreatedResponse {
  uploadId: string
  signedUrl: string
  fileName: string
}

export type UploadPhase = 'hashing' | 'creating' | 'uploading' | 'complete' | 'failed'

export const UPLOAD_STEPS = ['hashing', 'creating', 'uploading'] as const

export type UploadStep = (typeof UPLOAD_STEPS)[number]

export interface UploadQueueItem {
  id: string
  file: File
  phase: UploadPhase
  sha256: string | null
  uploadProgress: number
  result: SignedUrlCreatedResponse | null
  error: string | null
  failedAtPhase: UploadStep | null
}

export const MP4_MIME = 'video/mp4'

export function isMp4File(file: File): boolean {
  const lowerName = file.name.toLowerCase()
  return lowerName.endsWith('.mp4') || file.type === MP4_MIME
}

export function isActivePhase(phase: UploadPhase): boolean {
  return phase === 'hashing' || phase === 'creating' || phase === 'uploading'
}

export function isRemovablePhase(phase: UploadPhase): boolean {
  return phase === 'complete' || phase === 'failed'
}

/** Maps pipeline phase + S3 byte progress to a single 0–100 value for the UI bar. */
export function computeOverallProgress(phase: UploadPhase, uploadProgress: number): number {
  switch (phase) {
    case 'hashing':
      return 10
    case 'creating':
      return 30
    case 'uploading':
      return 30 + Math.round(uploadProgress * 0.7)
    case 'complete':
      return 100
    case 'failed':
      return 0
  }
}

export function uploadPhaseLabel(phase: UploadPhase): string {
  switch (phase) {
    case 'hashing':
      return 'Computing SHA-256'
    case 'creating':
      return 'Requesting upload URL'
    case 'uploading':
      return 'Uploading to storage'
    case 'complete':
      return 'Upload complete'
    case 'failed':
      return 'Upload failed'
  }
}

export const UPLOAD_STEP_LABELS: Record<UploadStep, string> = {
  hashing: 'Hash',
  creating: 'Sign URL',
  uploading: 'Upload',
}

export function stepStatus(
  step: UploadStep,
  phase: UploadPhase,
  failedAtPhase: UploadStep | null,
): 'pending' | 'active' | 'done' | 'failed' {
  const stepIndex = UPLOAD_STEPS.indexOf(step)

  if (phase === 'complete') {
    return 'done'
  }

  if (phase === 'failed' && failedAtPhase) {
    const failedIndex = UPLOAD_STEPS.indexOf(failedAtPhase)
    if (stepIndex < failedIndex) {
      return 'done'
    }
    if (stepIndex === failedIndex) {
      return 'failed'
    }
    return 'pending'
  }

  if (phase === 'hashing' || phase === 'creating' || phase === 'uploading') {
    const activeIndex = UPLOAD_STEPS.indexOf(phase)
    if (stepIndex < activeIndex) {
      return 'done'
    }
    if (stepIndex === activeIndex) {
      return 'active'
    }
    return 'pending'
  }

  return 'pending'
}
