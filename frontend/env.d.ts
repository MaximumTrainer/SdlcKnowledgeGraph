/// <reference types="vite/client" />

// The graph view hands its Cytoscape instance to the browser tests (#9), in test mode only.
interface Window {
  __cy?: unknown
  __cyReady?: boolean
}

// cytoscape-dagre ships no types; it is only ever handed to cytoscape.use().
declare module 'cytoscape-dagre' {
  import type { Ext } from 'cytoscape'
  const dagre: Ext
  export default dagre
}
