import { computed, inject, type ComputedRef } from 'vue'
import { AUTH_SESSION } from './session'
import { GRAPH_ADMIN, GRAPH_WRITE } from './scopes'

/**
 * Whether to offer the signed-in user the lifecycle's administration (#33): applying ontology
 * migrations and running the archive, which change the graph as a whole. The API needs graph:admin
 * with graph:write for both; never without a login, since the anonymous mode is read-only (#118).
 */
export const useCanAdmin = (): ComputedRef<boolean> => {
  const session = inject(AUTH_SESSION, null)
  return computed(
    () =>
      !!session &&
      session.scopes.value.includes(GRAPH_WRITE) &&
      session.scopes.value.includes(GRAPH_ADMIN)
  )
}
