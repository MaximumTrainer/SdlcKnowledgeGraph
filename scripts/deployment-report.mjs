#!/usr/bin/env node
/**
 * Builds the report the dogfood deploy posts to POST /api/v1/ingest/deployment (#7, FR4), so the
 * graph records its own deployments. The payload is documented in docs/ADAPTERS.md.
 *
 *   REPORT_ARTIFACTS="registry.fly.io/app@sha256:..." ... node scripts/deployment-report.mjs
 *
 * Reads the workflow's environment: GITHUB_REPOSITORY, GITHUB_ACTOR, and REPORT_COMMIT,
 * REPORT_ENVIRONMENT, REPORT_JOB_STATUS, REPORT_DEPLOYED_AT, REPORT_RUN_URL, REPORT_WORKFLOW_PATH and
 * REPORT_ARTIFACTS (image references by digest, separated by spaces or newlines). Prints the JSON.
 */
import { fileURLToPath } from 'node:url'

const SHORT_SHA = 7

/** `name@sha256:...` as the endpoint's artifact, or an error naming the reference that has no digest. */
const artifactOf = (reference, commit) => {
  const at = reference.lastIndexOf('@')
  if (at <= 0) throw new Error(`"${reference}" is not an image reference by digest (name@sha256:...)`)
  return { name: reference.slice(0, at), digest: reference.slice(at + 1), tag: commit.slice(0, SHORT_SHA) }
}

export const buildReport = ({
  repository,
  commit,
  environment,
  jobStatus,
  deployedAt,
  deployedBy,
  runUrl,
  workflowPath,
  artifacts,
}) => {
  const references = (artifacts ?? '').split(/\s+/).filter(Boolean)
  if (references.length === 0) {
    throw new Error('no image was pushed, so there is no deployment to report')
  }
  return {
    repository: `github.com/${repository}`,
    commitSha: commit,
    artifacts: references.map(reference => artifactOf(reference, commit)),
    environment,
    // GitHub's job status is success, failure or cancelled; anything short of success did not deploy.
    status: jobStatus === 'success' ? 'SUCCESS' : 'FAILED',
    deployedAt,
    deployedBy,
    runUrl,
    pipeline: { provider: 'github-actions', workflowPath },
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const env = process.env
  try {
    const report = buildReport({
      repository: env.GITHUB_REPOSITORY,
      commit: env.REPORT_COMMIT,
      environment: env.REPORT_ENVIRONMENT,
      jobStatus: env.REPORT_JOB_STATUS,
      deployedAt: env.REPORT_DEPLOYED_AT || new Date().toISOString(),
      deployedBy: env.GITHUB_ACTOR,
      runUrl: env.REPORT_RUN_URL,
      workflowPath: env.REPORT_WORKFLOW_PATH,
      artifacts: env.REPORT_ARTIFACTS,
    })
    process.stdout.write(`${JSON.stringify(report)}\n`)
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
