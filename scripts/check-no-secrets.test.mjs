import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRepo } from './test-support.mjs'

/**
 * `check-no-secrets` refuses a committed deployment configuration file that holds a credential
 * rather than a placeholder for one (#48, D8). secretlint already knows the shapes of real tokens;
 * this knows the shape of configuration, where a credential is a *value* under a key that says what
 * it is, and where the only acceptable value is a reference to the platform's secret store.
 *
 * Every credential below is assembled at run time, so this file does not trip the secret scanners
 * that run over it.
 */
describe('check-no-secrets', () => {
  let repo

  before(() => {
    repo = createRepo()
  })
  after(() => repo.cleanup())

  const check = paths => repo.run('check-no-secrets.mjs', paths)
  const literal = 'Zq7' + 'vR2mK9xLp4Tn'

  describe('refuses', () => {
    test('a literal password in the application configuration', () => {
      repo.stage('backend/src/main/resources/application.yml', `spring:\n  neo4j:\n    password: ${literal}\n`)

      const { status, stderr } = check(['backend/src/main/resources/application.yml'])

      assert.equal(status, 1)
      assert.match(stderr, /application\.yml:3/)
      assert.match(stderr, /password/)
    })

    test('a literal token in a fly.io file', () => {
      repo.stage('fly/fly.backend.toml', `[env]\n  API_TOKEN = "${literal}"\n`)

      assert.equal(check(['fly/fly.backend.toml']).status, 1)
    })

    test('a literal client secret in a workflow', () => {
      repo.stage('.github/workflows/deploy.yml', `env:\n  CLIENT_SECRET: ${literal}\n`)

      assert.equal(check(['.github/workflows/deploy.yml']).status, 1)
    })

    test('a private key block', () => {
      const begin = '-----BEGIN ' + 'RSA PRIVATE KEY-----'
      repo.stage('ops/tls.yml', `key: |\n  ${begin}\n  MIIEow\n`)

      const { status, stderr } = check(['ops/tls.yml'])

      assert.equal(status, 1)
      assert.match(stderr, /private key/)
    })

    test('a value with a known token prefix, under any key', () => {
      repo.stage('fly/bootstrap.sh', `curl -H "Authorization: Bearer ${'ghp' + '_'}${'a'.repeat(36)}" x\n`)

      const { status, stderr } = check(['fly/bootstrap.sh'])

      assert.equal(status, 1)
      assert.match(stderr, /GitHub token/)
    })

    test('a long base64 run, which is what most credentials look like once encoded', () => {
      const encoded = 'dGhpcyBpcyBub3QgYSByZWFs' + 'IHNlY3JldCBidXQgbG9va3MgbGlrZSBvbmU9'
      repo.stage('ops/values.yml', `blob: ${encoded}\n`)

      assert.equal(check(['ops/values.yml']).status, 1)
    })

    test('reads what is staged, not what is on disk', () => {
      repo.stage('fly/staged.toml', `password = "${literal}"\n`)
      repo.write('fly/staged.toml', 'password = "${NEO4J_PASSWORD}"\n')

      assert.equal(check(['fly/staged.toml']).status, 1)
    })
  })

  describe('allows', () => {
    test('a Spring placeholder, with or without a default', () => {
      repo.stage(
        'backend/src/main/resources/application-ok.yml',
        'password: ${NEO4J_PASSWORD}\nsecret: ${WEBHOOK_SECRET:}\ntoken: ${GITHUB_TOKEN:local-only}\n'
      )

      assert.equal(check(['backend/src/main/resources/application-ok.yml']).status, 0)
    })

    test('a GitHub Actions secret reference and a shell variable', () => {
      repo.stage(
        '.github/workflows/ok.yml',
        'env:\n  FLY_API_TOKEN: ${{ secrets.FLY_API_TOKEN }}\n  NEO4J_PASSWORD: "$NEO4J_PASSWORD"\n'
      )

      assert.equal(check(['.github/workflows/ok.yml']).status, 0)
    })

    test('short values that are settings rather than credentials', () => {
      repo.stage('.github/workflows/perms.yml', 'permissions:\n  id-token: write\n  token: none\n')

      assert.equal(check(['.github/workflows/perms.yml']).status, 0)
    })

    test('a pinned action and an image digest, which are long but hex', () => {
      repo.stage(
        '.github/workflows/pinned.yml',
        `steps:\n  - uses: actions/checkout@${'a1b2c3d4e5'.repeat(4)}\nimage: neo4j@sha256:${'0f'.repeat(32)}\n`
      )

      assert.equal(check(['.github/workflows/pinned.yml']).status, 0)
    })

    test('a line marked as not a secret', () => {
      repo.stage('ops/fixture.yml', `password: ${literal} # not-a-secret: documented test fixture\n`)

      assert.equal(check(['ops/fixture.yml']).status, 0)
    })

    test('files outside the deployment configuration, which secretlint covers', () => {
      repo.stage('backend/src/test/resources/application-test.yml', `password: ${literal}\n`)

      assert.equal(check(['backend/src/test/resources/application-test.yml']).status, 0)
    })

    test('a file being deleted, which has nothing left to leak', () => {
      assert.equal(check(['fly/gone.toml']).status, 0)
    })
  })
})
