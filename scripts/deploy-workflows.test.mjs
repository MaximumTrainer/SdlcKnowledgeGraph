import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'

/**
 * Every deploy goes through one gate (#48, FR9): `.github/workflows/deploy-on-green.yml` decides
 * whether a CI run may be deployed and which commit that is, so no deploy workflow restates the
 * rule and no deploy can take whatever `main` points at by the time it starts (D12).
 */
describe('deploy workflows', () => {
  const workflows = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../.github/workflows')
  const load = name => yaml.load(readFileSync(path.join(workflows, name), 'utf8'))
  const GATE = './.github/workflows/deploy-on-green.yml'

  test('the gate is a reusable workflow with the inputs and outputs a deploy needs', () => {
    const gate = load('deploy-on-green.yml')
    const call = gate.on.workflow_call
    assert.ok(call, 'deploy-on-green.yml is not a workflow_call workflow')
    assert.deepEqual(Object.keys(call.inputs).sort(), ['environment', 'image_tag_prefix'])
    assert.equal(call.inputs.environment.required, true)
    assert.deepEqual(Object.keys(call.outputs).sort(), ['image_tag', 'sha', 'should_deploy'])
  })

  test('every workflow that deploys is triggered by CI and asks the gate first', () => {
    const deploying = readdirSync(workflows)
      .filter(name => name.endsWith('.yml') && name !== 'deploy-on-green.yml')
      .filter(name => readFileSync(path.join(workflows, name), 'utf8').includes('flyctl deploy'))
    assert.deepEqual(deploying, ['deploy-dogfood.yml'])

    for (const name of deploying) {
      const workflow = load(name)
      assert.deepEqual(workflow.on.workflow_run.workflows, ['CI'], `${name} is not triggered by CI`)
      const gateJobs = Object.entries(workflow.jobs).filter(([, job]) => job.uses === GATE)
      assert.equal(gateJobs.length, 1, `${name} does not call the gate exactly once`)
      const [gateName] = gateJobs[0]

      for (const [jobName, job] of Object.entries(workflow.jobs)) {
        if (jobName === gateName) continue
        const needs = [job.needs].flat()
        assert.ok(needs.includes(gateName), `${name}: job ${jobName} does not wait for the gate`)
        assert.match(String(job.if), new RegExp(`needs\\.${gateName}\\.outputs\\.should_deploy == 'true'`))
        assert.doesNotMatch(JSON.stringify(job), /workflow_run\.head_sha/, `${name}: ${jobName} reads head_sha itself`)
      }
    }
  })
})
