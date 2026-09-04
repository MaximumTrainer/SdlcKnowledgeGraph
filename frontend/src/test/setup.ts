/**
 * Vitest setup: starts a Mock Service Worker server for the whole test run so that unit tests
 * never talk to a real backend. Add or override handlers per test with `server.use(...)`.
 */
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
