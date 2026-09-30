import type { NodeType } from '@/generated/ontology'

/**
 * The colour each node type is drawn in on the graph view (#9, FR4).
 *
 * Keyed by the generated `NodeType`, so a type added to the registry fails the typecheck here until
 * it has a colour, and `ontologyColours.spec.ts` holds the palette to the registry at run time too.
 * Red is left out on purpose: it marks what a blast radius reaches.
 */
export const ONTOLOGY_COLOURS: Record<NodeType, string> = {
  Repository: '#3182ce',
  Team: '#38a169',
  Service: '#805ad5',
  Pipeline: '#d69e2e',
  Artifact: '#dd6b20',
  Deployment: '#b83280',
  Environment: '#319795',
  CloudResource: '#c05621',
  ConfigurationItem: '#6b46c1',
  ChangeRequest: '#975a16',
  Incident: '#9b2c2c',
  Change: '#2c7a7b',
  PullRequest: '#434190',
  ExternalWorkItem: '#d53f8c',
  Library: '#63b3ed',
  IacFile: '#68d391',
  Ontology: '#4a5568',
  SyncRun: '#718096',
  ConnectorState: '#2d3748',
  ServicePrincipal: '#b794f4'
}

/** For a type the server knows and this build does not yet: drawn, just not told apart. */
export const FALLBACK_COLOUR = '#cbd5e0'

export const colourFor = (type: string): string =>
  (ONTOLOGY_COLOURS as Record<string, string>)[type] ?? FALLBACK_COLOUR
