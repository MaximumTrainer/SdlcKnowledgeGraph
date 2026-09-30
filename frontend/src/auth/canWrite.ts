import { computed, inject, type ComputedRef } from 'vue'
import { AUTH_SESSION } from './session'
import { GRAPH_WRITE } from './scopes'

/**
 * Whether to offer the signed-in user ways to change the graph (#116): only when their token holds
 * graph:write. Always without a login, since the API is then running its development bypass and
 * refuses nothing.
 */
export const useCanWrite = (): ComputedRef<boolean> => {
  const session = inject(AUTH_SESSION, null)
  return computed(() => !session || session.scopes.value.includes(GRAPH_WRITE))
}
