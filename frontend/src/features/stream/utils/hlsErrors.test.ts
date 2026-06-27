import { describe, expect, it } from 'vitest'
import Hls from 'hls.js'
import { describeHlsError } from '@/features/stream/utils/hlsErrors'

describe('describeHlsError', () => {
  it('returns null for non-fatal errors', () => {
    expect(
      describeHlsError({
        type: Hls.ErrorTypes.NETWORK_ERROR,
        details: Hls.ErrorDetails.MANIFEST_LOAD_ERROR,
        fatal: false,
      }),
    ).toBeNull()
  })

  it('describes manifest load failures', () => {
    const message = describeHlsError({
      type: Hls.ErrorTypes.NETWORK_ERROR,
      details: Hls.ErrorDetails.MANIFEST_LOAD_ERROR,
      fatal: true,
    })

    expect(message).toContain('manifest')
  })

  it('describes segment load failures', () => {
    const message = describeHlsError({
      type: Hls.ErrorTypes.NETWORK_ERROR,
      details: Hls.ErrorDetails.FRAG_LOAD_ERROR,
      fatal: true,
    })

    expect(message).toContain('segment')
  })

  it('describes media decode failures', () => {
    const message = describeHlsError({
      type: Hls.ErrorTypes.MEDIA_ERROR,
      details: Hls.ErrorDetails.BUFFER_ADD_CODEC_ERROR,
      fatal: true,
    })

    expect(message).toContain('decode')
  })
})
