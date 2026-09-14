/**
 * Parses any written form of a git remote into the parts that identify a Repository.
 *
 * A port of the backend's `GitRemoteParser`, tested against the same table
 * (`src/test/fixtures/git-remotes.json`). It exists so the editing screen can show the key a remote
 * will be stored under *before* saving, rather than leaving the person to discover it afterwards —
 * and because a preview that disagrees with what the API derives would be worse than none, the two
 * are held to one table rather than trusted to stay in step.
 */
export class InvalidGitRemoteError extends Error {
  constructor(
    readonly input: string,
    readonly reason: string
  ) {
    super(`'${input}' is not a git remote: ${reason}`)
    this.name = 'InvalidGitRemoteError'
  }
}

export interface GitRemote {
  host: string
  org: string
  name: string
  /** The identity key: what the graph stores the node under. */
  key: string
  /** The one form stored on the node, whatever form was supplied. */
  canonicalUrl: string
}

/** The host a bare `org/name` shorthand is assumed to be on. */
const DEFAULT_HOST = 'github.com'
const GIT_SUFFIX = '.git'
/** Two for the bare shorthand, three once a host is named. */
const MIN_SEGMENTS = 2

const GIT_SCHEMES = ['https', 'http', 'ssh', 'git']

/** `git@github.com:acme/payments.git` — what git itself writes for an SSH remote. */
const SCP_LIKE = /^[\w.-]+@([\w.-]+):([\w.-]+)\/([\w.-]+)$/
/** `https://github.com/acme/payments` and `ssh://git@github.com/acme/payments.git`. */
const WITH_SCHEME = /^(?:https|http|ssh|git):\/\/(?:[\w.-]+@)?([\w.-]+)\/([\w.-]+)\/([\w.-]+)$/i
/**
 * `github.com/acme/payments`, as written in prose. The host must carry a dot, which is what
 * separates it from a three-segment path that names no host at all.
 */
const HOST_AND_PATH = /^([\w-]+(?:\.[\w-]+)+)\/([\w.-]+)\/([\w.-]+)$/
/** `acme/payments`, which assumes DEFAULT_HOST. */
const SHORTHAND = /^([\w.-]+)\/([\w.-]+)$/

const SCHEME_PREFIX = /^([a-zA-Z][\w+.-]*):\/\//

/**
 * Why a string that looks URL-ish is still not a remote.
 *
 * Worth the extra work: "it needs an organisation and a repository name" tells someone who pasted a
 * link to a file what to paste instead, where a generic "unrecognisable" leaves them guessing.
 */
function rejectionReason(remote: string): string {
  const scheme = SCHEME_PREFIX.exec(remote)?.[1]
  if (scheme && !GIT_SCHEMES.includes(scheme.toLowerCase())) {
    return `'${scheme}' is not a scheme git speaks`
  }
  // The host is not a path segment. Counting it would tell someone who pasted a link to a page that
  // their URL points inside a repository, when it names no repository at all.
  const pathSegments = remote.split('://').pop()!.split('/').filter(Boolean)
  const segments = scheme ? pathSegments.slice(1) : pathSegments
  return segments.length < MIN_SEGMENTS
    ? 'it needs an organisation and a repository name'
    : 'it points inside a repository rather than at one'
}

export function parseGitRemote(input: string): GitRemote {
  const trimmed = input.trim()
  if (trimmed.length === 0) throw new InvalidGitRemoteError(input, 'it is empty')
  // Checked before the patterns rather than left to them, so the reason names the real problem
  // instead of the generic "unrecognisable" a failed match would give.
  if (/\s/.test(trimmed)) throw new InvalidGitRemoteError(input, 'it contains whitespace')

  // A trailing slash survives a copy and paste and means nothing.
  const remote = trimmed.replace(/\/$/, '')

  const matched =
    SCP_LIKE.exec(remote)?.slice(1) ??
    WITH_SCHEME.exec(remote)?.slice(1) ??
    HOST_AND_PATH.exec(remote)?.slice(1) ??
    SHORTHAND.exec(remote)
      ?.slice(1)
      .reduce<string[]>((parts, part) => [...parts, part], [DEFAULT_HOST])

  if (!matched) throw new InvalidGitRemoteError(input, rejectionReason(remote))

  const [host, org, rawName] = matched
  // Only a trailing `.git` is a suffix; a dot inside the name belongs to it.
  const name = (
    rawName.endsWith(GIT_SUFFIX) ? rawName.slice(0, -GIT_SUFFIX.length) : rawName
  ).toLowerCase()

  return {
    host: host.toLowerCase(),
    org: org.toLowerCase(),
    name,
    key: `${host.toLowerCase()}/${org.toLowerCase()}/${name}`,
    canonicalUrl: `https://${host.toLowerCase()}/${org.toLowerCase()}/${name}`
  }
}
