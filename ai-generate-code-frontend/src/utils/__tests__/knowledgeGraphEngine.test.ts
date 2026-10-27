import { describe, expect, it, vi } from 'vitest'
import {
  KnowledgeGraphEngine,
  buildAdjacency,
  clusterKeyOf,
  deriveClusters,
  deriveImportance,
  deriveTracePaths,
  type GraphEdgeInput,
  type GraphNodeInput,
} from '../knowledgeGraphEngine'

const node = (key: string, kind: string, sourceRef?: string): GraphNodeInput => ({
  key,
  name: key,
  kind,
  sourceRef: sourceRef ?? null,
})

describe('knowledgeGraphEngine 的布局推导', () => {
  describe('clusterKeyOf', () => {
    it('有路径时取首层目录', () => {
      expect(clusterKeyOf(node('a', 'file', 'src/main.js'))).toBe('src')
      expect(clusterKeyOf(node('b', 'file', 'agent/core/runtime.py'))).toBe('agent')
      // Windows 分隔符同样识别
      expect(clusterKeyOf(node('c', 'file', 'src\\utils\\x.ts'))).toBe('src')
    })

    it('路径只有文件名时归到 root', () => {
      expect(clusterKeyOf(node('a', 'file', 'index.html'))).toBe('root')
    })

    it('没有路径时退回 kind —— 至少让同类节点聚在一起', () => {
      expect(clusterKeyOf(node('a', 'endpoint'))).toBe('endpoint')
      expect(clusterKeyOf({ key: 'x', name: 'x' })).toBe('other')
    })
  })

  describe('deriveClusters', () => {
    it('簇按 id 排序，标签大写', () => {
      const clusters = deriveClusters([
        node('a', 'file', 'src/a.js'),
        node('b', 'file', 'agent/b.py'),
        node('c', 'file', 'src/c.js'),
      ])

      expect(clusters.map((c) => c.id)).toEqual(['agent', 'src'])
      expect(clusters.map((c) => c.label)).toEqual(['AGENT', 'SRC'])
    })

    it('root 的标签单独处理', () => {
      const clusters = deriveClusters([node('a', 'file', 'index.html')])
      expect(clusters[0]?.label).toBe('ROOT')
    })

    it('簇心分散开，不重叠在同一点', () => {
      const clusters = deriveClusters([
        node('a', 'file', 'src/a.js'),
        node('b', 'file', 'agent/b.py'),
        node('c', 'file', 'docs/c.md'),
      ])
      const positions = clusters.map((c) => `${Math.round(c.x)},${Math.round(c.y)}`)
      expect(new Set(positions).size).toBe(positions.length)
    })
  })

  describe('deriveImportance', () => {
    it('连接越多重要度越高，且都在 1~6', () => {
      const nodes = [node('hub', 'file'), node('mid', 'file'), node('leaf', 'file')]
      const edges: GraphEdgeInput[] = [
        { from: 'hub', to: 'mid', relation: 'imports' },
        { from: 'hub', to: 'leaf', relation: 'imports' },
        { from: 'mid', to: 'leaf', relation: 'imports' },
      ]

      const importance = deriveImportance(nodes, edges)

      // 三个点各连两条边，重要度应当相同
      expect(importance.get('hub')).toBe(importance.get('leaf'))
      for (const value of importance.values()) {
        expect(value).toBeGreaterThanOrEqual(1)
        expect(value).toBeLessThanOrEqual(6)
      }
    })

    it('孤立节点是最低档而不是 0', () => {
      const importance = deriveImportance([node('lonely', 'file')], [])
      expect(importance.get('lonely')).toBe(1)
    })

    it('只有连线的一头时也算度数', () => {
      const importance = deriveImportance(
        [node('a', 'file'), node('b', 'file')],
        [{ from: 'a', to: 'b', relation: 'calls' }],
      )
      expect(importance.get('a')).toBe(importance.get('b'))
    })
  })

  describe('buildAdjacency', () => {
    it('无向邻接：两个方向都能从一端走到另一端', () => {
      const adjacency = buildAdjacency(
        [node('a', 'file'), node('b', 'file')],
        [{ from: 'a', to: 'b', relation: 'imports' }],
      )

      expect(adjacency.get('a')?.has('b')).toBe(true)
      expect(adjacency.get('b')?.has('a')).toBe(true)
    })

    it('端点不在节点集合里的边被忽略，不会造出悬空邻接', () => {
      const adjacency = buildAdjacency(
        [node('a', 'file')],
        [{ from: 'a', to: 'ghost', relation: 'imports' }],
      )

      expect(adjacency.has('ghost')).toBe(false)
      expect(adjacency.get('a')?.size).toBe(0)
    })
  })

  describe('deriveTracePaths', () => {
    it('从 endpoint 出发沿边推导路径，并给出经过的边', () => {
      const nodes = [
        node('GET /users', 'endpoint'),
        node('UserController', 'class'),
        node('UserRepository', 'class'),
        node('PostgreSQL', 'table'),
      ]
      const edges: GraphEdgeInput[] = [
        { from: 'GET /users', to: 'UserController', relation: 'calls' },
        { from: 'UserController', to: 'UserRepository', relation: 'calls' },
        { from: 'UserRepository', to: 'PostgreSQL', relation: 'reads' },
      ]
      const adjacency = buildAdjacency(nodes, edges)

      const paths = deriveTracePaths(nodes, edges, adjacency)

      expect(paths).toHaveLength(1)
      expect(paths[0]?.key).toBe('GET /users')
      expect(paths[0]?.names).toEqual([
        'GET /users',
        'UserController',
        'UserRepository',
        'PostgreSQL',
      ])
      expect(paths[0]?.nodeKeys).toHaveLength(4)
      expect(paths[0]?.edgeKeys).toHaveLength(3)
    })

    it('没有 endpoint 就没有路径 —— 不硬造一条出来', () => {
      const nodes = [node('a', 'file'), node('b', 'file')]
      const edges: GraphEdgeInput[] = [{ from: 'a', to: 'b', relation: 'imports' }]

      expect(deriveTracePaths(nodes, edges, buildAdjacency(nodes, edges))).toEqual([])
    })

    it('路径不会绕回已访问的节点（避免环里死转）', () => {
      const nodes = [node('E', 'endpoint'), node('A', 'class'), node('B', 'class')]
      const edges: GraphEdgeInput[] = [
        { from: 'E', to: 'A', relation: 'calls' },
        { from: 'A', to: 'B', relation: 'calls' },
        { from: 'B', to: 'A', relation: 'calls' },
      ]
      const adjacency = buildAdjacency(nodes, edges)

      const paths = deriveTracePaths(nodes, edges, adjacency)

      expect(paths[0]?.nodeKeys).toEqual(['E', 'A', 'B'])
    })

    it('跳数受 maxHops 限制', () => {
      const nodes = [
        node('E', 'endpoint'),
        node('A', 'class'),
        node('B', 'class'),
        node('C', 'class'),
      ]
      const edges: GraphEdgeInput[] = [
        { from: 'E', to: 'A', relation: 'calls' },
        { from: 'A', to: 'B', relation: 'calls' },
        { from: 'B', to: 'C', relation: 'calls' },
      ]
      const adjacency = buildAdjacency(nodes, edges)

      const paths = deriveTracePaths(nodes, edges, adjacency, 2)

      expect(paths[0]?.nodeKeys).toHaveLength(3)
    })
  })
})

describe('图谱拖拽物理', () => {
  it('节点跟手，邻居参与弹簧模拟，松手后退火停止', () => {
    const ctx = new Proxy({}, { get: () => vi.fn(), set: () => true })
    const canvas = document.createElement('canvas')
    vi.spyOn(canvas, 'getContext').mockReturnValue(ctx as CanvasRenderingContext2D)
    const shell = document.createElement('div')
    vi.spyOn(shell, 'getBoundingClientRect').mockReturnValue({ width: 900, height: 600 } as DOMRect)
    const engine = new KnowledgeGraphEngine(canvas, shell)
    engine.load([node('a', 'file'), node('b', 'file')], [{ from: 'a', to: 'b', relation: 'calls' }])
    engine.resize()
    engine.fit()
    engine.tick(100)
    const first = engine.search('a')[0]!
    expect(first).toBeTruthy()
    // 通过绘制坐标驱动真实指针路径，不单测替身力模型。
    const nodes = (
      engine as unknown as { nodes: Array<{ x: number; y: number; sx: number; sy: number }> }
    ).nodes
    const a = nodes[0]!,
      b = nodes[1]!
    const old = { x: b.x, y: b.y }
    expect(engine.pointerDown(a.sx, a.sy)).toBe('node')
    engine.pointerMove(a.sx + 80, a.sy + 20, { x: 80, y: 20 })
    const target = { x: a.x, y: a.y }
    for (let i = 1; i <= 30; i++) engine.tick(100 + i * 16.67)
    expect(a.x).toBe(target.x)
    expect(a.y).toBe(target.y)
    expect(Math.hypot(b.x - old.x, b.y - old.y)).toBeGreaterThan(1)
    engine.pointerUp()
    for (let i = 31; i <= 650; i++) engine.tick(100 + i * 16.67)
    expect(nodes.every((n) => Number.isFinite(n.x) && Number.isFinite(n.y))).toBe(true)
    expect(engine.stats().heat).toBe(0)
  })
})

describe('图谱不按数量补节点', () => {
  function graph(nodes: GraphNodeInput[], edges: GraphEdgeInput[]) {
    const ctx = new Proxy({}, { get: () => vi.fn(), set: () => true })
    const canvas = document.createElement('canvas')
    vi.spyOn(canvas, 'getContext').mockReturnValue(ctx as CanvasRenderingContext2D)
    const engine = new KnowledgeGraphEngine(canvas, document.createElement('div'))
    engine.load(nodes, edges)
    return engine
  }
  it('默认展示结构，全局保留完整数据，搜索仍可找到辅助函数', () => {
    const engine = graph(
      [node('main', 'file'), node('scene', 'module'), node('helper', 'function')],
      [
        { from: 'main', to: 'scene', relation: 'imports' },
        { from: 'scene', to: 'helper', relation: 'contains' },
      ],
    )
    expect(engine.stats().visible).toBe(2)
    expect(engine.stats().edges).toBe(1)
    expect(engine.search('helper')).toHaveLength(1)
    engine.setMode('global')
    expect(engine.stats().visible).toBe(3)
    expect(engine.stats().edges).toBe(2)
  })
  it('选中后只保留直接邻居，不加二跳、同目录和无关节点', () => {
    const nodes = [
      node('root', 'file'),
      node('child', 'function'),
      node('grandchild', 'function'),
      ...Array.from({ length: 30 }, (_, i) => node('unrelated' + i, 'function')),
    ]
    const engine = graph(nodes, [
      { from: 'root', to: 'child', relation: 'calls' },
      { from: 'child', to: 'grandchild', relation: 'calls' },
    ])
    engine.selectByKey('root')
    expect(engine.stats().visible).toBe(2)
    engine.selectByKey('unrelated0')
    expect(engine.stats().visible).toBe(1)
    expect(engine.stats().edges).toBe(0)
  })
  it('没有结构类型时保留已有内容，不猜测或凭数量裁剪', () => {
    expect(graph([node('core', 'function')], []).stats().visible).toBe(1)
    expect(graph([], []).stats().visible).toBe(0)
  })
})
