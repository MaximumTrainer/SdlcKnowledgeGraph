import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { buildReport } from './deployment-report.mjs'
import { scriptPath } from './test-support.mjs'

/**
 * The report the dogfood deploy posts to POST /api/v1/ingest/deployment after every deploy (#7, FR4),
 * built from what the workflow knows. The endpoint's payload is documented in docs/ADAPTERS.md.
 */
describe('deployment-report', () => {
  const inputs = {
    repository: 'MaximumTrainer/SdlcKnowledgeGraph',
    commit: '5efa09d68706304efec8ec74349dd72ed44912cb',
    environment: 'dogfood',
    jobStatus: 'success',
    deployedAt: '2026-09-29T12:00:00Z',
    deployedBy: 'octocat',
    runUrl: 'https://github.com/MaximumTrainer/SdlcKnowledgeGraph/actions/runs/42',
    workflowPath: '.github/workflows/deploy-dogfood.yml',
    artifacts: 'registry.fly.io/sdlc-graph-backend@sha256:aaa registry.fly.io/sdlc-graph@sha256:bbb',
  }

  test('reports every image deployed, by digest, tagged with the short commit', () => {
    const report = buildReport(inputs)

    assert.deepEqual(report, {
      repository: 'github.com/MaximumTrainer/SdlcKnowledgeGraph',
      commitSha: inputs.commit,
      artifacts: [
        { name: 'registry.fly.io/sdlc-graph-backend', digest: 'sha256:aaa', tag: '5efa09d' },
        { name: 'registry.fly.io/sdlc-graph', digest: 'sha256:bbb', tag: '5efa09d' },
      ],
      environment: 'dogfood',
      status: 'SUCCESS',
      deployedAt: '2026-09-29T12:00:00Z',
      deployedBy: 'octocat',
      runUrl: inputs.runUrl,
      pipeline: { provider: 'github-actions', workflowPath: '.github/workflows/deploy-dogfood.yml' },
    })
  })

  test('reports a job that did not succeed as FAILED, whatever GitHub called it', () => {
    for (const jobStatus of ['failure', 'cancelled']) {
      assert.equal(buildReport({ ...inputs, jobStatus }).status, 'FAILED', jobStatus)
    }
  })

  test('accepts the images one per line as well as space-separated', () => {
    const artifacts = 'registry.fly.io/a@sha256:1\n\nregistry.fly.io/b@sha256:2\n'

    assert.deepEqual(
      buildReport({ ...inputs, artifacts }).artifacts.map(a => a.name),
      ['registry.fly.io/a', 'registry.fly.io/b'],
    )
  })

  test('refuses to build a report with no images, which the endpoint would refuse anyway', () => {
    assert.throws(() => buildReport({ ...inputs, artifacts: ' ' }), /no image was pushed/)
  })

  test('refuses an image reference without a digest', () => {
    assert.throws(() => buildReport({ ...inputs, artifacts: 'registry.fly.io/a:latest' }), /registry.fly.io\/a:latest/)
  })

  test('prints the report as JSON from the environment the workflow sets', () => {
    const result = spawnSync(process.execPath, [scriptPath('deployment-report.mjs')], {
      encoding: 'utf8',
      env: {
        ...process.env,
        GITHUB_REPOSITORY: inputs.repository,
        REPORT_COMMIT: inputs.commit,
        REPORT_ENVIRONMENT: inputs.environment,
        REPORT_JOB_STATUS: 'failure',
        REPORT_DEPLOYED_AT: inputs.deployedAt,
        GITHUB_ACTOR: inputs.deployedBy,
        REPORT_RUN_URL: inputs.runUrl,
        REPORT_WORKFLOW_PATH: inputs.workflowPath,
        REPORT_ARTIFACTS: inputs.artifacts,
      },
    })

    assert.equal(result.status, 0, result.stderr)
    const report = JSON.parse(result.stdout)
    assert.equal(report.status, 'FAILED')
    assert.equal(report.artifacts.length, 2)
  })

  test('exits non-zero, saying why, when there is nothing to report', () => {
    const result = spawnSync(process.execPath, [scriptPath('deployment-report.mjs')], {
      encoding: 'utf8',
      env: { ...process.env, REPORT_ARTIFACTS: '' },
    })

    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /no image was pushed/)
  })
})
