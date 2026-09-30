import type { AxiosError, AxiosInstance } from 'axios'
import type { AuthSession } from './session'

const here = () => `${window.location.pathname}${window.location.search}`

/**
 * Sends the signed-in user's access token with every request [client] makes, and answers a 401 by
 * restarting the login, returning to where the user was (#114, FR-2). A 401 here means the token
 * expired or was revoked beyond what silent renewal could fix, and signing in again is the only
 * thing that helps, so showing it as an error would be showing the user a dead end.
 */
export const attachAuth = (
  client: AxiosInstance,
  session: AuthSession,
  currentLocation: () => string = here
): void => {
  client.interceptors.request.use(async request => {
    const token = await session.accessToken()
    if (token) request.headers.set('Authorization', `Bearer ${token}`)
    return request
  })
  client.interceptors.response.use(
    response => response,
    async (error: AxiosError) => {
      if (error.response?.status === 401) await session.signIn(currentLocation())
      return Promise.reject(error)
    }
  )
}
