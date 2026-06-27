import { describe, expect, it } from 'vitest'
import {
  isFailed,
  isInProgress,
  isPlayable,
  isTerminal,
  playerStatusMessage,
  videoStatusBadgeClass,
  videoStatusLabel,
} from '@/features/stream/types'

describe('stream types', () => {
  it('marks PLAY_READY as playable', () => {
    expect(isPlayable('PLAY_READY')).toBe(true)
    expect(isPlayable('AWAITING_UPLOAD')).toBe(false)
    expect(isPlayable('TRANSCODING_IN_PROGRESS')).toBe(false)
    expect(isPlayable('FAILED')).toBe(false)
  })

  it('classifies in-progress and terminal statuses', () => {
    expect(isInProgress('AWAITING_UPLOAD')).toBe(true)
    expect(isInProgress('TRANSCODING_IN_PROGRESS')).toBe(true)
    expect(isInProgress('PLAY_READY')).toBe(false)
    expect(isTerminal('PLAY_READY')).toBe(true)
    expect(isTerminal('FAILED')).toBe(true)
    expect(isTerminal('AWAITING_UPLOAD')).toBe(false)
    expect(isFailed('FAILED')).toBe(true)
    expect(isFailed('PLAY_READY')).toBe(false)
  })

  it('maps status to human-readable labels', () => {
    expect(videoStatusLabel('PLAY_READY')).toBe('Ready')
    expect(videoStatusLabel('TRANSCODING_IN_PROGRESS')).toBe('Transcoding')
    expect(videoStatusLabel('FAILED')).toBe('Failed')
    expect(videoStatusLabel('AWAITING_UPLOAD')).toBe('Awaiting upload')
  })

  it('assigns badge classes per status', () => {
    expect(videoStatusBadgeClass('PLAY_READY')).toContain('emerald')
    expect(videoStatusBadgeClass('TRANSCODING_IN_PROGRESS')).toContain('amber')
    expect(videoStatusBadgeClass('FAILED')).toContain('red')
    expect(videoStatusBadgeClass('AWAITING_UPLOAD')).toContain('slate')
  })

  it('explains non-playable statuses for the player panel', () => {
    expect(playerStatusMessage('FAILED')).toContain('Transcoding failed')
    expect(playerStatusMessage('TRANSCODING_IN_PROGRESS')).toContain('transcoding')
    expect(playerStatusMessage('AWAITING_UPLOAD')).toContain('Upload has not finished')
  })
})
