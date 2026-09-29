import { describe, expect, it } from 'vitest'
import { formatAge, formatDuration, formatInstant, toInstant } from './time'

/**
 * The run history is read by someone scanning for the slow one or the old one, so durations and ages
 * are short and in the largest unit that matters, and a missing value says why it is missing.
 */
describe('formatDuration', () => {
  it('says a run that has not finished has no duration yet', () => {
    expect(formatDuration(null)).toBe('—')
  })

  it('keeps milliseconds for a run under a second', () => {
    expect(formatDuration(450)).toBe('450 ms')
  })

  it('uses the two largest units past that', () => {
    expect(formatDuration(42_000)).toBe('42 s')
    expect(formatDuration(90_000)).toBe('1 m 30 s')
    expect(formatDuration(3_600_000)).toBe('1 h')
    expect(formatDuration(3_900_000)).toBe('1 h 5 m')
    expect(formatDuration(90_000_000)).toBe('1 d 1 h')
  })
})

describe('formatAge', () => {
  it('says never rather than inventing an age for a connector that never succeeded', () => {
    expect(formatAge(null)).toBe('never')
  })

  it('says how long ago', () => {
    expect(formatAge(10_800)).toBe('3 h ago')
    expect(formatAge(0)).toBe('0 s ago')
  })
})

describe('formatInstant', () => {
  it('renders an instant in UTC, to the second, whatever the reader’s zone', () => {
    expect(formatInstant('2026-09-01T10:00:00.123Z')).toBe('2026-09-01 10:00:00 UTC')
  })

  it('renders nothing for no instant', () => {
    expect(formatInstant(null)).toBe('')
  })
})

describe('toInstant', () => {
  it('reads a datetime-local value as UTC, which is what the filter labels say', () => {
    expect(toInstant('2026-09-01T10:00')).toBe('2026-09-01T10:00:00Z')
  })

  it('leaves an empty filter out', () => {
    expect(toInstant('')).toBeUndefined()
  })
})
