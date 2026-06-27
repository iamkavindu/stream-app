import { describe, expect, it } from 'vitest'
import { sha256Hex } from '@/shared/utils/sha256'

describe('sha256Hex', () => {
  it('returns known digest for empty input', async () => {
    const blob = new Blob([])
    await expect(sha256Hex(blob)).resolves.toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    )
  })

  it('returns lowercase hex for non-empty input', async () => {
    const blob = new Blob(['hello'])
    const digest = await sha256Hex(blob)
    expect(digest).toMatch(/^[0-9a-f]{64}$/)
    expect(digest).toBe('2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824')
  })
})
