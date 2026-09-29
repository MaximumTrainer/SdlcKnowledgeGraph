#!/usr/bin/env node
/**
 * Decides whether a CI run may be deployed, and which commit that is (#48, FR9; docs/DEPLOYMENT.md
 * D12). Run by `.github/workflows/deploy-on-green.yml`, the one gate every deploy workflow calls.
 *
 * Only a green run of a push to `main` is deployed, and what is deployed is that run's own commit,
 * never whatever `main` points at by the time the deploy starts: a later push may not have passed.
 *
 * Reads GATE_CONCLUSION, GATE_BRANCH, GATE_EVENT and GATE_SHA (the triggering workflow_run's), plus
 * GATE_ENVIRONMENT and GATE_IMAGE_TAG_PREFIX. Writes should_deploy, sha and image_tag to
 * $GITHUB_OUTPUT, and says why on stdout.
 */
import { appendFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const FULL_SHA = /^[0-9a-f]{40}$/

const skipped = reason => ({ shouldDeploy: false, sha: '', imageTag: '', reason })

export const decide = ({ conclusion, branch, event, sha, environment, imageTagPrefix = '' }) => {
  if (!environment) throw new Error('no environment given: the gate has to say what it is deploying to')
  if (conclusion !== 'success') return skipped(`Not deploying: CI concluded ${conclusion}`)
  if (branch !== 'main') return skipped(`Not deploying: CI ran on ${branch}, not main`)
  if (event !== 'push') return skipped(`Not deploying: the CI run was a ${event}, not a push`)
  // Checked last, and only for a run that would deploy: a sha that is not a full one would make the
  // deploy check out and build whatever that name resolves to, which is exactly what D12 forbids.
  if (!FULL_SHA.test(sha ?? '')) throw new Error(`"${sha}" is not a full commit sha`)
  return {
    shouldDeploy: true,
    sha,
    imageTag: `${imageTagPrefix}${sha}`,
    reason: `CI passed on main at ${sha}: deploying it to ${environment}`,
  }
}

const main = () => {
  const env = process.env
  let decision
  try {
    decision = decide({
      conclusion: env.GATE_CONCLUSION,
      branch: env.GATE_BRANCH,
      event: env.GATE_EVENT,
      sha: env.GATE_SHA,
      environment: env.GATE_ENVIRONMENT,
      imageTagPrefix: env.GATE_IMAGE_TAG_PREFIX,
    })
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
  console.log(decision.reason)
  const outputs = `should_deploy=${decision.shouldDeploy}\nsha=${decision.sha}\nimage_tag=${decision.imageTag}\n`
  if (env.GITHUB_OUTPUT) appendFileSync(env.GITHUB_OUTPUT, outputs)
  else process.stdout.write(outputs)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main()
