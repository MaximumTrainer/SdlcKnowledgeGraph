/**
 * Where a node's page is (#85).
 *
 * A key with slashes (`github.com/acme/payments`) is written into the route as it is, one segment per
 * part, which the `:id+` route reads back joined. A key holding `//` - an ExternalWorkItem's URI -
 * cannot be: the empty segment matches no route. Such a key is encoded as a single segment instead,
 * which the router decodes back to the key.
 */
export const nodeRoute = (type: string, key: string, sub?: string): string => {
  const segment = key.includes('//') ? encodeURIComponent(key) : key
  return `/nodes/${type}/${segment}${sub ? `/${sub}` : ''}`
}
