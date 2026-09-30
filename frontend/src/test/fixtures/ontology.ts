import type { Ontology } from '@/services/api'

/**
 * A small ontology used by the view tests.
 *
 * `Sample` exists only to exercise one property of every declared type, so the mapping from ontology
 * type to form input is tested once rather than incidentally through whichever real node type
 * happens to use a given type today.
 */
export const ontologyFixture: Ontology = {
  version: '1.0.0',
  nodeTypes: [
    {
      name: 'Team',
      description: 'A group that owns things',
      identity: ['name'],
      meta: false,
      properties: [
        { name: 'name', type: 'string', required: true, description: null },
        { name: 'email', type: 'string', required: false, description: null }
      ]
    },
    {
      name: 'Repository',
      description: 'A git repository',
      identity: ['host', 'org', 'name'],
      meta: false,
      properties: [
        { name: 'url', type: 'string', required: true, description: null },
        { name: 'host', type: 'string', required: true, description: null },
        { name: 'org', type: 'string', required: true, description: null },
        { name: 'name', type: 'string', required: true, description: null },
        { name: 'description', type: 'string', required: false, description: null }
      ]
    },
    {
      name: 'Sample',
      description: 'One property of every declared type',
      identity: ['name'],
      meta: false,
      properties: [
        {
          name: 'name',
          type: 'string',
          required: true,
          description: "The sample's name, unique among samples",
          examples: ['alpha', 'beta']
        },
        { name: 'count', type: 'int', required: false, description: null },
        { name: 'enabled', type: 'boolean', required: false, description: null },
        { name: 'seenAt', type: 'instant', required: false, description: null },
        { name: 'topics', type: 'string[]', required: false, description: null },
        {
          name: 'stage',
          type: 'string',
          required: false,
          description: 'Where the sample is in its life',
          enum: ['draft', 'live'],
          examples: ['live']
        }
      ]
    },
    {
      name: 'Gadget',
      description: 'A type with a required closed set',
      identity: ['name'],
      meta: false,
      properties: [
        { name: 'name', type: 'string', required: true, description: null },
        {
          name: 'kind',
          type: 'string',
          required: true,
          description: 'What sort of gadget this is',
          enum: ['library', 'api'],
          examples: ['api']
        }
      ]
    }
  ],
  edgeTypes: [
    {
      name: 'OWNED_BY',
      description: null,
      from: ['Repository'],
      to: ['Team'],
      inverse: 'OWNS',
      properties: []
    }
  ]
}
