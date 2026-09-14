/**
 * Parses any written form of a git remote into the parts that identify a Repository.
 *
 * A port of the backend's `GitRemoteParser`, tested against the same table
 * (`src/test/fixtures/git-remotes.json`). It exists so the editing screen can show the key a remote
 * will be stored under *before* saving, rather than after the API has decided.
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

export function parseGitRemote(input: string): GitRemote {
  throw new InvalidGitRemoteError(input, 'not implemented yet')
}
