import { describe, expect, it } from 'vitest'
import { fitWithin } from '../src/utils/serviceImage'

describe('service image sizing', () => {
  it('keeps small images at their original size', () => {
    expect(fitWithin(800, 600)).toEqual({ width: 800, height: 600 })
  })

  it('fits large images inside 1600x900 without cropping', () => {
    expect(fitWithin(4000, 3000)).toEqual({ width: 1200, height: 900 })
    expect(fitWithin(3000, 1000)).toEqual({ width: 1600, height: 533 })
  })

  it('rejects undecodable dimensions', () => {
    expect(() => fitWithin(0, 900)).toThrow('无法读取图片尺寸')
  })
})
