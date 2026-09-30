/**
 * A stand-in for Cytoscape in the unit tests (#9). jsdom has no canvas, so the real library cannot
 * draw there; this records what the view asks of it - the elements it adds, the layouts it runs, the
 * classes it sets - and lets a test tap a node the way a person would.
 *
 * It implements only what `GraphView` uses. A view that reaches for more fails loudly here, which is
 * the point: the browser suite is where the real library is exercised (e2e/tests/graph-view.spec.ts).
 */
export interface FakeElement {
  group: 'nodes' | 'edges'
  data: Record<string, unknown> & { id: string }
  classes: Set<string>
  position: { x: number; y: number }
}

export interface FakeCollection {
  length: number
  elements: FakeElement[]
  addClass(names: string): FakeCollection
  removeClass(names: string): FakeCollection
  hasClass(name: string): boolean
  remove(): FakeCollection
  id(): string
  data(): Record<string, unknown>
  position(position?: { x: number; y: number }): { x: number; y: number }
}

type Handler = (event: { target: { id(): string } }) => void

const names = (value: string) => value.split(/\s+/).filter(Boolean)

export class FakeCy {
  readonly byId = new Map<string, FakeElement>()
  readonly layouts: Record<string, unknown>[] = []
  readonly handlers: { event: string; selector: string | null; handler: Handler }[] = []
  fits = 0
  destroyed = false

  constructor(readonly options: Record<string, unknown>) {}

  private collection(elements: FakeElement[]): FakeCollection {
    const self: FakeCollection = {
      length: elements.length,
      elements,
      addClass: value => {
        elements.forEach(element => names(value).forEach(name => element.classes.add(name)))
        return self
      },
      removeClass: value => {
        elements.forEach(element => names(value).forEach(name => element.classes.delete(name)))
        return self
      },
      hasClass: name => elements.some(element => element.classes.has(name)),
      remove: () => {
        elements.forEach(element => this.byId.delete(element.data.id))
        return self
      },
      id: () => elements[0]?.data.id ?? '',
      data: () => elements[0]?.data ?? {},
      position: position => {
        if (position && elements[0]) elements[0].position = { ...position }
        return elements[0]?.position ?? { x: 0, y: 0 }
      }
    }
    return self
  }

  add(definitions: { group: 'nodes' | 'edges'; data: FakeElement['data']; classes?: string }[]) {
    const added = definitions.map(definition => {
      if (this.byId.has(definition.data.id))
        throw new Error(`${definition.data.id} is already drawn`)
      const element: FakeElement = {
        group: definition.group,
        data: { ...definition.data },
        classes: new Set(names(definition.classes ?? '')),
        position: { x: 0, y: 0 }
      }
      this.byId.set(element.data.id, element)
      return element
    })
    return this.collection(added)
  }

  elements() {
    return this.collection([...this.byId.values()])
  }

  nodes() {
    return this.collection([...this.byId.values()].filter(element => element.group === 'nodes'))
  }

  edges(selector?: string) {
    const edges = [...this.byId.values()].filter(element => element.group === 'edges')
    if (selector === '[inferred]')
      return this.collection(edges.filter(edge => edge.data.inferred !== undefined))
    if (selector) throw new Error(`the fake does not understand the selector ${selector}`)
    return this.collection(edges)
  }

  getElementById(id: string) {
    const element = this.byId.get(id)
    return this.collection(element ? [element] : [])
  }

  layout(options: Record<string, unknown>) {
    this.layouts.push(options)
    return {
      run: () => {
        ;(options.stop as (() => void) | undefined)?.()
      }
    }
  }

  on(event: string, selectorOrHandler: string | Handler, handler?: Handler) {
    if (typeof selectorOrHandler === 'function') {
      this.handlers.push({ event, selector: null, handler: selectorOrHandler })
    } else {
      this.handlers.push({ event, selector: selectorOrHandler, handler: handler as Handler })
    }
  }

  fit() {
    this.fits += 1
  }

  destroy() {
    this.destroyed = true
  }

  /** What a person tapping (or double-tapping) a drawn node sets off. */
  trigger(event: 'tap' | 'dbltap', id: string) {
    this.handlers
      .filter(entry => entry.event === event && entry.selector === 'node')
      .forEach(entry => entry.handler({ target: { id: () => id } }))
  }

  nodeIds() {
    return this.nodes().elements.map(element => element.data.id)
  }
}

export const fakeCytoscape = {
  instances: [] as FakeCy[],
  extensions: [] as unknown[],
  factory: Object.assign(
    (options: Record<string, unknown>) => {
      const cy = new FakeCy(options)
      fakeCytoscape.instances.push(cy)
      return cy
    },
    {
      use: (extension: unknown) => {
        fakeCytoscape.extensions.push(extension)
      }
    }
  ),
  /** The instance the view created last. */
  last(): FakeCy {
    const cy = fakeCytoscape.instances.at(-1)
    if (!cy) throw new Error('the view created no Cytoscape instance')
    return cy
  },
  reset() {
    fakeCytoscape.instances.length = 0
  }
}
