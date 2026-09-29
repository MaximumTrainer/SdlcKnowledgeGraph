import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'

/**
 * What amtool cannot check in the Alertmanager config (#44, FR12): that a down instance silences the
 * error budget alerts it causes, and that no webhook URL is committed. The URL is a secret, so every
 * receiver reads it from a file the runtime writes.
 */
describe('Alertmanager config', () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const config = yaml.load(readFileSync(path.join(root, 'ops/alertmanager/alertmanager.yml'), 'utf8'))

  test('a down instance inhibits the error budget alerts of the same job', () => {
    const rule = (config.inhibit_rules ?? []).find(r => (r.source_matchers ?? []).includes('alertname="InstanceDown"'))
    assert.ok(rule, 'no inhibit rule has InstanceDown as its source')
    assert.deepEqual(rule.target_matchers, ['slo=~".+"'])
    assert.deepEqual(rule.equal, ['job'])
  })

  test('every webhook reads its URL from a file, never from the config', () => {
    const webhooks = config.receivers.flatMap(r => r.webhook_configs ?? [])
    assert.ok(webhooks.length > 0, 'no webhook receivers')
    for (const webhook of webhooks) {
      assert.equal(webhook.url, undefined, 'a webhook URL is committed')
      assert.equal(webhook.url_file, '/etc/alertmanager/webhook-url')
    }
  })
})
