import { describe, expect, it } from 'vitest'
import { NODE_TYPES } from '@/generated/ontology'
import { colourFor, FALLBACK_COLOUR, ONTOLOGY_COLOURS } from './ontologyColours'

/**
 * The graph view colours a node by its type (#9, FR4). The palette is keyed by the generated
 * `NodeType`, so the compiler already refuses one that misses a type; these hold it to the registry
 * at run time as well, and keep two types from being told apart by nothing.
 */
describe('ontology colours', () => {
  it('has a colour for every node type the registry declares', () => {
    for (const type of NODE_TYPES) {
      expect(ONTOLOGY_COLOURS[type], type).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('names no type the registry does not declare', () => {
    expect(Object.keys(ONTOLOGY_COLOURS).sort()).toEqual([...NODE_TYPES].sort())
  })

  it('gives every type its own colour', () => {
    const colours = NODE_TYPES.map(type => ONTOLOGY_COLOURS[type].toLowerCase())
    expect(new Set(colours).size).toBe(colours.length)
  })

  it('reads a type by name, and a type it has never heard of as the fallback', () => {
    expect(colourFor('Repository')).toBe(ONTOLOGY_COLOURS.Repository)
    expect(colourFor('SomethingNew')).toBe(FALLBACK_COLOUR)
    expect(NODE_TYPES.map(type => ONTOLOGY_COLOURS[type])).not.toContain(FALLBACK_COLOUR)
  })
})
