/**
 * Durations, ages and instants as the run history shows them (#29).
 *
 * Short, in the two largest units that matter, and in UTC: the history is read by someone scanning a
 * column for the slow run or the old connector, and times read in UTC agree with the API, the logs
 * and whoever the link was sent to.
 */
const UNITS: [label: string, seconds: number][] = [
  ['d', 86_400],
  ['h', 3_600],
  ['m', 60],
  ['s', 1]
]

const compact = (totalSeconds: number): string => {
  let rest = Math.max(0, Math.round(totalSeconds))
  const parts: string[] = []
  for (const [label, size] of UNITS) {
    const count = Math.floor(rest / size)
    rest -= count * size
    if (count > 0 || (parts.length > 0 && parts.length < 2)) parts.push(`${count} ${label}`)
    if (parts.length === 2) break
  }
  // Drop a trailing zero unit: "1 h", not "1 h 0 m".
  const trimmed = parts.filter((part, index) => index === 0 || !part.startsWith('0 '))
  return trimmed.length > 0 ? trimmed.join(' ') : '0 s'
}

/** How long a run took. A run still going has no duration yet. */
export const formatDuration = (ms: number | null | undefined): string => {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${ms} ms`
  return compact(ms / 1000)
}

/** How long ago a connector last succeeded; "never" rather than a made-up age. */
export const formatAge = (seconds: number | null | undefined): string =>
  seconds === null || seconds === undefined ? 'never' : `${compact(seconds)} ago`

/** A threshold, as a length of time rather than an age. */
export const formatSeconds = (seconds: number): string => compact(seconds)

/** An ISO-8601 instant, in UTC to the second. */
export const formatInstant = (iso: string | null | undefined): string => {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.toISOString().slice(0, 19).replace('T', ' ')} UTC`
}

/**
 * A `datetime-local` value as the instant the API expects. The inputs are labelled UTC and read as
 * such, so the same filter means the same window for everyone who is sent it.
 */
export const toInstant = (local: string): string | undefined => {
  if (!local) return undefined
  const withSeconds = local.length === 16 ? `${local}:00` : local
  return `${withSeconds}Z`
}
