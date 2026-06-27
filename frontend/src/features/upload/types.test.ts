import { describe, expect, it } from 'vitest'
import {
  computeOverallProgress,
  isActivePhase,
  isMp4File,
  isRemovablePhase,
  stepStatus,
  uploadPhaseLabel,
} from '@/features/upload/types'

describe('isMp4File', () => {
  it('accepts .mp4 extension', () => {
    const file = new File(['x'], 'clip.mp4', { type: 'application/octet-stream' })
    expect(isMp4File(file)).toBe(true)
  })

  it('accepts video/mp4 mime type', () => {
    const file = new File(['x'], 'clip.bin', { type: 'video/mp4' })
    expect(isMp4File(file)).toBe(true)
  })

  it('rejects non-mp4 files', () => {
    const file = new File(['x'], 'clip.mov', { type: 'video/quicktime' })
    expect(isMp4File(file)).toBe(false)
  })
})

describe('uploadPhaseLabel', () => {
  it('returns a label for each upload phase', () => {
    expect(uploadPhaseLabel('hashing')).toContain('SHA-256')
    expect(uploadPhaseLabel('uploading')).toContain('Uploading')
    expect(uploadPhaseLabel('complete')).toContain('complete')
    expect(uploadPhaseLabel('failed')).toContain('failed')
  })
})

describe('computeOverallProgress', () => {
  it('maps pipeline phases to overall progress', () => {
    expect(computeOverallProgress('hashing', 0)).toBe(10)
    expect(computeOverallProgress('creating', 0)).toBe(30)
    expect(computeOverallProgress('uploading', 100)).toBe(100)
    expect(computeOverallProgress('uploading', 50)).toBe(65)
    expect(computeOverallProgress('complete', 0)).toBe(100)
    expect(computeOverallProgress('failed', 0)).toBe(0)
  })
})

describe('phase helpers', () => {
  it('identifies active and removable phases', () => {
    expect(isActivePhase('uploading')).toBe(true)
    expect(isActivePhase('complete')).toBe(false)
    expect(isRemovablePhase('complete')).toBe(true)
    expect(isRemovablePhase('failed')).toBe(true)
    expect(isRemovablePhase('hashing')).toBe(false)
  })
})

describe('stepStatus', () => {
  it('marks earlier steps done when upload fails during signing', () => {
    expect(stepStatus('hashing', 'failed', 'creating')).toBe('done')
    expect(stepStatus('creating', 'failed', 'creating')).toBe('failed')
    expect(stepStatus('uploading', 'failed', 'creating')).toBe('pending')
  })

  it('marks active step while upload is in progress', () => {
    expect(stepStatus('hashing', 'creating', null)).toBe('done')
    expect(stepStatus('creating', 'creating', null)).toBe('active')
    expect(stepStatus('uploading', 'creating', null)).toBe('pending')
  })
})
