import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'
import {
  DASHBOARD,
  DATASOURCES,
  DOCS,
  checkDashboard,
  documentedMetrics,
  metricNames,
  recordingRules,
} from './dashboard.mjs'

/**
 * The Grafana dashboard (#29, FR9) reads only what the API publishes. Every metric a panel's query
 * names must be in the Metrics table of docs/OBSERVABILITY.md, so a renamed meter, a dashboard typo
 * and a metric nobody documented all fail here rather than as an empty panel nobody notices.
 */
describe('dashboard', () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const read = file => readFileSync(path.join(root, file), 'utf8')

  const panel = (expr, extra = {}) => ({
    type: 'timeseries',
    title: 'A panel',
    datasource: { type: 'prometheus', uid: 'prometheus' },
    targets: [{ refId: 'A', expr, datasource: { type: 'prometheus', uid: 'prometheus' } }],
    ...extra,
  })
  const dashboard = panels => ({ uid: 'sdlc-sync', title: 'SDLC sync', panels })
  const documented = new Set(['sdlc_sync_runs_total', 'sdlc_sync_duration_seconds', 'sdlc_graph_nodes'])

  describe('metricNames', () => {
    test('names the metrics a query reads, not its functions, labels, ranges or keywords', () => {
      assert.deepEqual(
        metricNames('sum by (connector, status) (rate(sdlc_sync_runs_total{status!="SUCCESS", mode=~"FULL|x"}[$__rate_interval]))'),
        ['sdlc_sync_runs_total'],
      )
    })

    test('finds every metric in a query that joins or divides', () => {
      assert.deepEqual(
        metricNames('sum(rate(a_total[5m])) / on (connector) group_left (version) sum without (le) (b_bucket) > bool 0'),
        ['a_total', 'b_bucket'],
      )
    })

    test('reads a histogram quantile down to its bucket series', () => {
      assert.deepEqual(
        metricNames('histogram_quantile(0.95, sum by (le, connector) (rate(sdlc_sync_duration_seconds_bucket[5m])))'),
        ['sdlc_sync_duration_seconds_bucket'],
      )
    })

    test('reads a metric named only inside its selector', () => {
      assert.deepEqual(metricNames('{__name__="sdlc_graph_nodes", type="Team"}'), ['sdlc_graph_nodes'])
    })
  })

  describe('documentedMetrics', () => {
    test('takes the first column of the Metrics table and nothing else', () => {
      const markdown = [
        '## Events',
        '| `not.a.metric` | x |',
        '## Metrics',
        '| Metric | Labels | What it counts |',
        '|---|---|---|',
        '| `sdlc_build_info` | `version` | Always 1. See `sdlc_other`. |',
        '| `sdlc_graph_nodes` | `type` | Nodes. |',
        '## Health',
        '| `sdlc_health` | x |',
      ].join('\n')

      assert.deepEqual([...documentedMetrics(markdown)].sort(), ['sdlc_build_info', 'sdlc_graph_nodes'])
    })

    test('refuses a document with no Metrics table', () => {
      assert.throws(() => documentedMetrics('# Observability\n'), /no Metrics table/)
    })
  })

  describe('checkDashboard', () => {
    test('passes a dashboard that reads documented metrics, histogram series included', () => {
      const problems = checkDashboard(
        dashboard([
          panel('sum(rate(sdlc_sync_runs_total[5m]))'),
          panel('histogram_quantile(0.95, sum by (le) (rate(sdlc_sync_duration_seconds_bucket[5m])))'),
        ]),
        { documented, datasource: 'prometheus' },
      )

      assert.deepEqual(problems, [])
    })

    test('names the panel and the metric when a query reads something undocumented', () => {
      const problems = checkDashboard(dashboard([panel('sdlc_sync_rnus_total', { title: 'Runs' })]), {
        documented,
        datasource: 'prometheus',
      })

      assert.deepEqual(problems, ['panel "Runs" reads sdlc_sync_rnus_total, which docs/OBSERVABILITY.md does not list'])
    })

    test('refuses a histogram suffix on a metric that is not documented', () => {
      const problems = checkDashboard(dashboard([panel('rate(sdlc_graph_edges_bucket[5m])', { title: 'Edges' })]), {
        documented,
        datasource: 'prometheus',
      })

      assert.equal(problems.length, 1)
    })

    test('refuses a panel with no query, or one on another datasource', () => {
      const problems = checkDashboard(
        dashboard([
          panel('sdlc_graph_nodes', { title: 'Nodes', targets: [] }),
          panel('sdlc_graph_nodes', { title: 'Elsewhere', datasource: { type: 'prometheus', uid: 'other' } }),
        ]),
        { documented, datasource: 'prometheus' },
      )

      assert.deepEqual(problems, [
        'panel "Nodes" has no query',
        'panel "Elsewhere" does not use the provisioned datasource "prometheus"',
      ])
    })

    test('checks panels inside a row as well as at the top level', () => {
      const problems = checkDashboard(
        dashboard([{ type: 'row', title: 'Graph', panels: [panel('nope_total', { title: 'Inner' })] }]),
        { documented, datasource: 'prometheus' },
      )

      assert.deepEqual(problems, ['panel "Inner" reads nope_total, which docs/OBSERVABILITY.md does not list'])
    })

    test('refuses a dashboard without a uid, or without panels', () => {
      assert.deepEqual(checkDashboard({ title: 'x', panels: [] }, { documented, datasource: 'prometheus' }), [
        'the dashboard has no uid',
        'the dashboard has no panels',
      ])
    })
  })

  describe('recordingRules', () => {
    test('wraps each query as a recording rule promtool can parse, with Grafana intervals made concrete', () => {
      const rules = yaml.load(
        recordingRules(dashboard([panel('sum(rate(sdlc_sync_runs_total[$__rate_interval]))', { title: 'Runs by status' })])),
      )

      assert.deepEqual(rules, {
        groups: [
          {
            name: 'dashboard',
            rules: [{ record: 'dashboard:panel_1_A', expr: 'sum(rate(sdlc_sync_runs_total[5m]))' }],
          },
        ],
      })
    })
  })

  describe('the committed dashboard', () => {
    const committed = JSON.parse(read(DASHBOARD))
    const datasources = yaml.load(read(DATASOURCES))
    const prometheus = datasources.datasources.find(source => source.type === 'prometheus')

    test('reads only metrics docs/OBSERVABILITY.md lists, through the provisioned datasource', () => {
      assert.deepEqual(checkDashboard(committed, { documented: documentedMetrics(read(DOCS)), datasource: prometheus.uid }), [])
    })

    test('has the panels FR9 asks for', () => {
      const exprs = committed.panels.flatMap(p => [p, ...(p.panels ?? [])]).flatMap(p => p.targets ?? []).map(t => t.expr)
      const read = new Set(exprs.flatMap(metricNames))
      for (const metric of [
        'sdlc_sync_runs_total',
        'sdlc_sync_duration_seconds_bucket',
        'sdlc_sync_freshness_seconds',
        'sdlc_sync_errors_total',
        'sdlc_graph_nodes',
      ]) {
        assert.ok(read.has(metric), `no panel reads ${metric}`)
      }
    })

    test('points its datasource at the monitoring service of the compose file', () => {
      assert.equal(prometheus.url, 'http://monitoring:9090')
    })

    test('runs Grafana from an exactly pinned image in the monitoring profile, viewers only for anonymous users', () => {
      const grafana = yaml.load(read('compose.yaml')).services.grafana
      assert.match(grafana.image, /^grafana\/grafana:\d+\.\d+\.\d+$/)
      assert.deepEqual(grafana.profiles, ['monitoring'])
      assert.equal(grafana.environment.GF_AUTH_ANONYMOUS_ENABLED, 'true')
      assert.equal(grafana.environment.GF_AUTH_ANONYMOUS_ORG_ROLE, 'Viewer')
    })
  })
})
