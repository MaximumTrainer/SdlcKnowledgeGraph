/**
 * Vitest setup: starts a Mock Service Worker server for the whole test run so that unit tests
 * never talk to a real backend. Add or override handlers per test with `server.use(...)`.
 *
 * Every view is mounted as a signed-in user who may write, as the default stack serves it (#118). A
 * test about another kind of user provides its own session, and one about a deployment with no login
 * (the anonymous read-only mode) provides none: `provide: providing(null)`.
 */
import { config } from '@vue/test-utils'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'
import { providing, READ_WRITE, sessionWith } from './authSession'

config.global.provide = providing(sessionWith(READ_WRITE, 'dan'))

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
