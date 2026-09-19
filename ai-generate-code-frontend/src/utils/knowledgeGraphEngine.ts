/**
 * Canvas 图谱：节点斥力、连线弹簧与弱中心力。
 * 拖动节点直接跟手，邻居参与物理模拟；松手后逐渐回稳，静止时停止绘制。
 */

/** 引擎看到的节点。字段对应知识图谱接口的数据。 */
export interface GraphNodeInput {
  key: string
  name: string
  kind?: string | null
  summary?: string | null
  /** CODE_EVIDENCE / AI_INFERENCE */
  evidence?: string | null
  sourceRef?: string | null
}

export interface GraphEdgeInput {
  from: string
  to: string
  relation?: string | null
  evidence?: string | null
  sourceRef?: string | null
}

/** 引擎内部节点：带物理状态与布局参数。 */
interface SimNode {
  id: string
  name: string
  kind: string
  cluster: string
  evidence: string
  path: string
  summary: string
  importance: number
  x: number
  y: number
  vx: number
  vy: number
  homeX: number
  homeY: number
  sx: number
  sy: number
  simIndex: number
  _cx?: number
  _cy?: number
}

interface SimEdge {
  a: string
  b: string
  A: SimNode
  B: SimNode
  type: string
  curve: number
  phase: number
  bias: number
}

interface Cluster {
  id: string
  label: string
  x: number
  y: number
  color: [number, number, number]
}

interface DragInfluence {
  n: SimNode
  weight: number
  startX: number
  startY: number
  phase: number
}

interface DragState {
  originX: number
  originY: number
  lastX: number
  lastY: number
  dx: number
  dy: number
  ivx: number
  ivy: number
  influence: DragInfluence[]
}

export interface GraphStats {
  lowPower: boolean
  adaptiveLowQuality: boolean
  dpr: number
  visible: number
  edges: number
  frames: number
  avgDrawMs: number
  animating: boolean
  heat: number
}

export interface DrawerInfo {
  kind: string
  name: string
  path: string
  evidence: string
  summary: string
  incoming: number
  outgoing: number
  dependsOn: string[]
  usedBy: string[]
  sourceRef: string
}

/** 一组静态分析出来的调用路径，用于 Trace 高亮。 */
export interface TracePath {
  key: string
  label: string
  nodeKeys: string[]
}

/** 搜索命中的节点（对外形状，不暴露内部物理状态）。 */
export interface GraphSearchHit {
  key: string
  name: string
  kind: string
}

/**
 * 节点属于哪个簇。
 *
 * 规则：有源码路径就取首层目录（同目录的模块聚在一起），
 * 没有就退回 kind —— 至少让同类型的节点聚拢。
 */
export function clusterKeyOf(node: GraphNodeInput): string {
  const ref = (node.sourceRef ?? '').trim()
  if (ref) {
    const segments = ref.replace(/\\/g, '/').split('/')
    if (segments.length > 1 && segments[0]) {
      return segments[0].toLowerCase()
    }
    return 'root'
  }
  return (node.kind ?? 'other').toLowerCase()
}

/** 由节点集合推导簇：簇心按圆环铺开，避免数量少时挤在中心。 */
export function deriveClusters(nodes: GraphNodeInput[]): Cluster[] {
  const clusterIds = Array.from(new Set(nodes.map(clusterKeyOf))).sort()
  return clusterIds.map((id, index) => {
    const angle = (index / Math.max(1, clusterIds.length)) * Math.PI * 2
    const radius = 150 + clusterIds.length * 26
    return {
      id,
      label: id === 'root' ? 'ROOT' : id.toUpperCase(),
      x: Math.cos(angle) * radius,
      y: Math.sin(angle) * radius,
      color: CLUSTER_COLORS[index % CLUSTER_COLORS.length] ?? [140, 140, 160],
    }
  })
}

/**
 * 由连接度推导重要度（1~6）。
 *
 * 原型的 importance 是演示数据里写死的；真实图谱里「连得多」就是「更要紧」，
 * 它决定节点半径与标签出现时机。
 */
export function deriveImportance(nodes: GraphNodeInput[], edges: GraphEdgeInput[]): Map<string, number> {
  const degree = new Map<string, number>()
  for (const edge of edges) {
    degree.set(edge.from, (degree.get(edge.from) ?? 0) + 1)
    degree.set(edge.to, (degree.get(edge.to) ?? 0) + 1)
  }
  const maxDegree = Math.max(1, ...Array.from(degree.values()))
  const result = new Map<string, number>()
  for (const node of nodes) {
    const links = degree.get(node.key) ?? 0
    result.set(node.key, Math.max(1, Math.round((links / maxDegree) * 5) + 1))
  }
  return result
}

/**
 * 静态推导可高亮的调用路径：从 endpoint 节点沿出边贪心走到叶子。
 *
 * 原型的路径是写死的演示数据。这里按图的真实结构走，最多 6 跳；
 * 前端必须标注它是「静态分析路径」，不能表现成实时请求追踪 —— 它确实不是。
 */
export function deriveTracePaths(
  nodes: GraphNodeInput[],
  edges: GraphEdgeInput[],
  adjacency: Map<string, Set<string>>,
  maxHops = 6,
): Array<{ key: string; names: string[]; nodeKeys: string[]; edgeKeys: string[] }> {
  const byKey = new Map(nodes.map((n) => [n.key, n]))
  const endpoints = nodes.filter((n) => (n.kind ?? '').toLowerCase() === 'endpoint').slice(0, 8)
  const results: Array<{ key: string; names: string[]; nodeKeys: string[]; edgeKeys: string[] }> = []

  for (const start of endpoints) {
    const names = [start.name]
    const nodeKeys = [start.key]
    const edgeKeys: string[] = []
    const seen = new Set<string>([start.key])
    let current = start

    for (let hop = 0; hop < maxHops; hop++) {
      const next = Array.from(adjacency.get(current.key) ?? [])
        .map((id) => byKey.get(id))
        .filter((n): n is GraphNodeInput => !!n && !seen.has(n.key))
        .sort((a, b) => (adjacency.get(b.key)?.size ?? 0) - (adjacency.get(a.key)?.size ?? 0))[0]
      if (!next) {
        break
      }
      const edge = edges.find(
        (e) =>
          (e.from === current.key && e.to === next.key) || (e.to === current.key && e.from === next.key),
      )
      if (edge) {
        edgeKeys.push(`${edge.from}|${edge.to}`)
      }
      seen.add(next.key)
      nodeKeys.push(next.key)
      names.push(next.name)
      current = next
    }

    if (edgeKeys.length > 0) {
      results.push({ key: start.key, names, nodeKeys, edgeKeys })
    }
  }
  return results
}

/** 无向邻接表。 */
export function buildAdjacency(nodes: GraphNodeInput[], edges: GraphEdgeInput[]): Map<string, Set<string>> {
  const adjacency = new Map<string, Set<string>>(nodes.map((n) => [n.key, new Set<string>()]))
  for (const edge of edges) {
    if (!adjacency.has(edge.from) || !adjacency.has(edge.to)) {
      continue
    }
    adjacency.get(edge.from)!.add(edge.to)
    adjacency.get(edge.to)!.add(edge.from)
  }
  return adjacency
}

const CLUSTER_COLORS: Array<[number, number, number]> = [
  [104, 130, 195],
  [92, 169, 183],
  [96, 158, 119],
  [147, 111, 185],
  [186, 133, 93],
  [166, 100, 125],
]

/** 调整力与阻尼后，需要回归拖拽和回稳测试。 */
const CELL = 176
const REP_RANGE = 184
const HEAT_DECAY = 0.982
const HEAT_MIN = 0.014
const DAMP = 0.86
const DAMP_DRAG = 0.82
/** 默认先展示结构层，函数细节通过搜索或选中节点查看。 */
const STRUCTURE_KINDS = new Set(['file', 'module', 'class', 'endpoint', 'service', 'table', 'database', 'agent'])
/** 超过这个节点数就降到 dpr=1。 */
const LOW_DPR_THRESHOLD = 140

export class KnowledgeGraphEngine {
  private canvas: HTMLCanvasElement
  private ctx: CanvasRenderingContext2D
  private shell: HTMLElement

  private nodes: SimNode[] = []
  private edges: SimEdge[] = []
  private adj = new Map<string, Set<string>>()
  private nodeById = new Map<string, SimNode>()
  private clusters: Cluster[] = []

  private W = 0
  private H = 0
  private dpr = 1
  private lowPower = false
  private adaptiveLowQuality = false

  private cam = { x: 0, y: 0, scale: 1 }
  private camTarget = { x: 0, y: 0, scale: 1 }

  private isPanning = false
  private lastMouse = { x: 0, y: 0 }
  private dragNode: SimNode | null = null
  private dragState: DragState | null = null
  private hoverNode: SimNode | null = null
  private selected: SimNode | null = null
  private mode: 'local' | 'global' = 'local'
  private animFrame: number | null = null
  private physicsHeat = 0.68
  private lastTime = 0
  private lastPaint = 0
  private lineMotionUntil = 0
  private lastLabelPaint = 0

  private traceActive = false
  private traceT = 0
  private tracePaths = new Map<string, { nodeSet: Set<string>; names: string[]; segments: SimEdge[] }>()

  private visibleCache: SimNode[] = []
  private visibleSetCache = new Set<string>()
  private visibleEdgesCache: SimEdge[] = []
  private focusDistCache: Map<string, number> | null = null
  private clusterMembersVisible = new Map<string, SimNode[]>()

  private perf = { frames: 0, avgDrawMs: 0, pairChecks: 0 }

  /** 外部回调：需要重绘时由组件驱动（组件持有 rAF 的所有权）。 */
  onNeedsFrame: (() => void) | null = null

  constructor(canvas: HTMLCanvasElement, shell: HTMLElement) {
    this.canvas = canvas
    this.shell = shell
    const ctx = canvas.getContext('2d', { alpha: true, desynchronized: true })
    if (!ctx) {
      throw new Error('无法获取 2D 绘图上下文')
    }
    this.ctx = ctx

    const nav = navigator as Navigator & { deviceMemory?: number }
    this.lowPower =
      (nav.deviceMemory != null && nav.deviceMemory <= 4) ||
      (nav.hardwareConcurrency != null && nav.hardwareConcurrency <= 4)
  }

  /* ============================ 数据装载 ============================ */

  /**
   * 装载知识图谱数据。
   *
   * 与原型的差别：原型的节点是写死的演示数据（含 cluster / importance / methods），
   * 这里全部从真实图谱推导 ——
   * - cluster 取源码路径的首层目录（同一目录的模块聚在一起），没有路径时退回 kind；
   * - importance 取连接度，连得多的节点画得大、更早显示标签；
   * - type 直接用后端给的 kind。
   */
  load(nodes: GraphNodeInput[], edges: GraphEdgeInput[]): void {
    this.clusters = deriveClusters(nodes)
    const importance = deriveImportance(nodes, edges)

    const membersOf = new Map<string, GraphNodeInput[]>()
    nodes.forEach((node) => {
      const cluster = clusterKeyOf(node)
      if (!membersOf.has(cluster)) {
        membersOf.set(cluster, [])
      }
      membersOf.get(cluster)!.push(node)
    })

    this.nodes = nodes.map((node) => {
      const cluster = clusterKeyOf(node)
      const clusterDef =
        this.clusters.find((c) => c.id === cluster) ?? { id: cluster, label: cluster, x: 0, y: 0, color: [140, 140, 160] as [number, number, number] }
      const siblings = membersOf.get(cluster) ?? []
      const index = siblings.indexOf(node)
      // 黄金角螺旋铺开，避免同簇节点初始重叠
      const angle = index * 2.399
      const radius = 30 + Math.sqrt(index + 1) * 28
      const x = clusterDef.x + Math.cos(angle) * radius
      const y = clusterDef.y + Math.sin(angle) * radius
      return {
        id: node.key,
        name: node.name,
        kind: node.kind ?? 'symbol',
        cluster,
        evidence: node.evidence ?? 'AI_INFERENCE',
        path: node.sourceRef ?? '',
        summary: node.summary ?? '',
        importance: importance.get(node.key) ?? 1,
        x,
        y,
        vx: 0,
        vy: 0,
        homeX: x,
        homeY: y,
        sx: 0,
        sy: 0,
        simIndex: 0,
      }
    })

    this.nodeById = new Map(this.nodes.map((n) => [n.id, n]))
    this.edges = edges
      .filter((edge) => this.nodeById.has(edge.from) && this.nodeById.has(edge.to))
      .map((edge) => ({
        a: edge.from,
        b: edge.to,
        A: this.nodeById.get(edge.from)!,
        B: this.nodeById.get(edge.to)!,
        type: edge.relation ?? 'related',
        // 曲率与相位由 key 派生而不是随机：同一张图每次打开形状一致，便于对照
        curve: (hashUnit(edge.from + edge.to) - 0.5) * 0.2,
        phase: hashUnit(edge.to + edge.from) * Math.PI * 2,
        bias: (hashUnit(edge.from + edge.to + 'b') - 0.5) * 0.36,
      }))

    this.adj = buildAdjacency(nodes, this.edges.map((e) => ({ from: e.a, to: e.b, relation: e.type })))
    this.tracePaths = this.buildTracePaths()
    this.rebuildState()
  }

  /**
   * 把纯函数推导出的路径转成渲染需要的形状（节点集合 + 边段）。
   */
  private buildTracePaths(): Map<string, { nodeSet: Set<string>; names: string[]; segments: SimEdge[] }> {
    const inferred = deriveTracePaths(
      this.nodes.map((n) => ({
        key: n.id,
        name: n.name,
        kind: n.kind,
        sourceRef: n.path,
        summary: n.summary,
        evidence: n.evidence,
      })),
      this.edges.map((e) => ({ from: e.a, to: e.b, relation: e.type })),
      this.adj,
    )

    const result = new Map<string, { nodeSet: Set<string>; names: string[]; segments: SimEdge[] }>()
    for (const path of inferred) {
      const segments: SimEdge[] = []
      for (const edgeKey of path.edgeKeys) {
        const [from, to] = edgeKey.split('|')
        const edge = this.edges.find(
          (e) => (e.a === from && e.b === to) || (e.a === to && e.b === from),
        )
        if (edge) {
          segments.push(edge)
        }
      }
      result.set(path.key, {
        nodeSet: new Set(path.nodeKeys),
        names: path.names,
        segments,
      })
    }
    return result
  }

  /** 可选的 Trace 路径列表（供下拉框）。 */
  listTracePaths(): TracePath[] {
    return Array.from(this.tracePaths.entries()).map(([key, value]) => ({
      key,
      label: value.names[0] ?? key,
      nodeKeys: Array.from(value.nodeSet),
    }))
  }

  /* ============================ 尺寸与相机 ============================ */

  private desiredDpr(): number {
    const cap =
      this.lowPower || this.adaptiveLowQuality || this.visibleCache.length > LOW_DPR_THRESHOLD ? 1 : 1.15
    return Math.min(window.devicePixelRatio || 1, cap)
  }

  resize(): void {
    const rect = this.shell.getBoundingClientRect()
    const nextDpr = this.desiredDpr()
    const width = Math.max(1, rect.width)
    const height = Math.max(1, rect.height)
    this.W = width
    this.H = height
    if (
      this.canvas.width !== Math.round(width * nextDpr) ||
      this.canvas.height !== Math.round(height * nextDpr)
    ) {
      this.dpr = nextDpr
      this.canvas.width = Math.round(width * nextDpr)
      this.canvas.height = Math.round(height * nextDpr)
      this.canvas.style.width = `${width}px`
      this.canvas.style.height = `${height}px`
      this.ctx.setTransform(nextDpr, 0, 0, nextDpr, 0, 0)
    }
  }

  private worldToScreen(n: SimNode): { x: number; y: number } {
    return { x: this.W / 2 + (n.x + this.cam.x) * this.cam.scale, y: this.H / 2 + (n.y + this.cam.y) * this.cam.scale }
  }

  private screenToWorld(x: number, y: number): { x: number; y: number } {
    return { x: (x - this.W / 2) / this.cam.scale - this.cam.x, y: (y - this.H / 2) / this.cam.scale - this.cam.y }
  }

  private updateScreenPositions(): void {
    const ox = this.W / 2 + this.cam.x * this.cam.scale
    const oy = this.H / 2 + this.cam.y * this.cam.scale
    const scale = this.cam.scale
    for (const node of this.visibleCache) {
      node.sx = ox + node.x * scale
      node.sy = oy + node.y * scale
    }
  }

  private smoothCamera(): void {
    this.cam.x += (this.camTarget.x - this.cam.x) * 0.18
    this.cam.y += (this.camTarget.y - this.cam.y) * 0.18
    this.cam.scale += (this.camTarget.scale - this.cam.scale) * 0.16
  }

  private cameraMoving(): boolean {
    return (
      Math.abs(this.cam.x - this.camTarget.x) > 0.08 ||
      Math.abs(this.cam.y - this.camTarget.y) > 0.08 ||
      Math.abs(this.cam.scale - this.camTarget.scale) > 0.001
    )
  }

  fit(): void {
    const visible = this.visibleNodes()
    if (!visible.length) {
      return
    }
    let minX = Infinity
    let maxX = -Infinity
    let minY = Infinity
    let maxY = -Infinity
    for (const node of visible) {
      minX = Math.min(minX, node.x)
      maxX = Math.max(maxX, node.x)
      minY = Math.min(minY, node.y)
      maxY = Math.max(maxY, node.y)
    }
    const scale = Math.min(
      (this.W - 180) / (maxX - minX + 120),
      (this.H - 180) / (maxY - minY + 120),
      1.16,
    )
    this.camTarget = {
      x: -(minX + maxX) / 2,
      y: -(minY + maxY) / 2,
      scale: Math.max(0.44, scale),
    }
    this.physicsHeat = Math.max(this.physicsHeat, 0.22)
    this.requestFrame()
  }

  private focusCamera(node: SimNode, scale = 1.28): void {
    this.camTarget = { x: -node.x, y: -node.y, scale: Math.max(scale, this.camTarget.scale) }
    this.physicsHeat = Math.max(this.physicsHeat, 0.36)
    this.requestFrame()
  }

  zoomBy(factor: number): void {
    this.camTarget.scale = Math.max(0.34, Math.min(2.6, this.camTarget.scale * factor))
    this.requestFrame()
  }

  reset(): void {
    this.selected = null
    this.hoverNode = null
    this.traceActive = false
    for (const node of this.nodes) {
      node.vx = 0
      node.vy = 0
      node.x = node.homeX
      node.y = node.homeY
    }
    this.physicsHeat = 0.65
    this.rebuildState()
    this.resize()
    this.fit()
  }

  setMode(mode: 'local' | 'global'): void {
    this.mode = mode
    this.physicsHeat = 0.38
    this.rebuildState()
    this.resize()
    this.fit()
  }

  getMode(): 'local' | 'global' {
    return this.mode
  }

  /* ============================ 可见性状态 ============================ */

  /** 默认看结构，选中后只看直接关联；不按数量或空间距离补节点。 */
  private rebuildState(): void {
    let list: SimNode[]
    if (this.mode === 'global') {
      list = this.nodes.slice()
    } else {
      const structure = this.nodes.filter(node => STRUCTURE_KINDS.has(node.kind.toLowerCase()))
      const ids = this.selected
        ? new Set([this.selected.id, ...(this.adj.get(this.selected.id) ?? [])])
        : new Set((structure.length ? structure : this.nodes).map(node => node.id))
      if (this.traceActive && this.currentTrace()) {
        for (const id of this.currentTrace()!.nodeSet) ids.add(id)
      }
      list = this.nodes.filter(node => ids.has(node.id))
    }

    this.visibleCache = list
    this.visibleSetCache = new Set(list.map((n) => n.id))
    list.forEach((node, index) => {
      node.simIndex = index
    })
    this.visibleEdgesCache = this.edges.filter(
      (e) => this.visibleSetCache.has(e.a) && this.visibleSetCache.has(e.b),
    )
    this.focusDistCache = this.selected ? this.hopDistance(this.selected, 3) : null
    this.clusterMembersVisible = new Map(
      this.clusters.map((c) => [c.id, list.filter((n) => n.cluster === c.id)]),
    )
  }

  private visibleNodes(): SimNode[] {
    if (!this.visibleCache.length) {
      this.rebuildState()
    }
    return this.visibleCache
  }

  private hopDistance(from: SimNode, max = 3): Map<string, number> {
    const distance = new Map<string, number>([[from.id, 0]])
    const queue: string[] = [from.id]
    for (let head = 0; head < queue.length; head++) {
      const id = queue[head]!
      const current = distance.get(id)!
      if (current >= max) {
        continue
      }
      for (const next of this.adj.get(id) ?? []) {
        if (!distance.has(next)) {
          distance.set(next, current + 1)
          queue.push(next)
        }
      }
    }
    return distance
  }

  /** 选中节点后，把邻居摆到同心环上 —— 这是「聚焦」的视觉来源。 */
  /* ============================ 物理 ============================ */

  private applyRepulsion(heat: number, dt: number): void {
    const grid = new Map<number, SimNode[]>()
    for (const node of this.visibleCache) {
      const cx = Math.floor(node.x / CELL)
      const cy = Math.floor(node.y / CELL)
      const key = (cx + 32768) * 65536 + (cy + 32768)
      let bucket = grid.get(key)
      if (!bucket) {
        grid.set(key, (bucket = []))
      }
      bucket.push(node)
      node._cx = cx
      node._cy = cy
    }

    for (const a of this.visibleCache) {
      if (a === this.dragNode) {
        continue
      }
      for (let gx = a._cx! - 1; gx <= a._cx! + 1; gx++) {
        for (let gy = a._cy! - 1; gy <= a._cy! + 1; gy++) {
          const bucket = grid.get((gx + 32768) * 65536 + (gy + 32768))
          if (!bucket) {
            continue
          }
          for (const b of bucket) {
            if (b.simIndex <= a.simIndex) {
              continue
            }
            this.perf.pairChecks++
            const dx = b.x - a.x
            const dy = b.y - a.y
            const d2 = dx * dx + dy * dy + 0.25
            if (d2 > REP_RANGE * REP_RANGE) {
              continue
            }
            const d = Math.sqrt(d2)
            const ux = dx / d
            const uy = dy / d
            const minD = 18 + (a.importance + b.importance) * 1.8
            const rep = (this.mode === 'global' ? 310 : 390) * heat / (d2 * 0.105 + 1)
            a.vx -= ux * rep * dt
            a.vy -= uy * rep * dt
            b.vx += ux * rep * dt
            b.vy += uy * rep * dt
            if (d < minD) {
              // 硬重叠时额外推开，避免节点叠在一起
              const push = (minD - d) * 0.017
              a.vx -= ux * push
              a.vy -= uy * push
              b.vx += ux * push
              b.vy += uy * push
            }
          }
        }
      }
    }
  }

  private buildDragState(root: SimNode, world: { x: number; y: number }): DragState {
    const hops = this.hopDistance(root, 3)
    const influence: DragInfluence[] = []
    for (const node of this.visibleCache) {
      if (node === root) {
        continue
      }
      const hop = hops.get(node.id)
      let weight = hop === 1 ? 0.42 : hop === 2 ? 0.17 : hop === 3 ? 0.065 : 0
      const distance = Math.hypot(node.x - root.x, node.y - root.y)
      if (!weight && distance < 215) {
        weight = 0.022 * (1 - distance / 215)
      }
      if (weight > 0) {
        influence.push({
          n: node,
          weight,
          startX: node.x,
          startY: node.y,
          phase: (node.simIndex % 7) * 0.71,
        })
      }
    }
    return {
      originX: world.x,
      originY: world.y,
      lastX: world.x,
      lastY: world.y,
      dx: 0,
      dy: 0,
      ivx: 0,
      ivy: 0,
      influence,
    }
  }

  /** 一次物理步进。heat 退到阈值以下且没有拖拽时直接返回 —— 静止即零计算。 */
  private applyPhysics(dt: number): void {
    const visible = this.visibleCache
    if (!visible.length) {
      return
    }
    this.physicsHeat = this.dragNode ? Math.max(0.65, this.physicsHeat) : this.physicsHeat * Math.pow(HEAT_DECAY, dt)
    if (this.physicsHeat < HEAT_MIN) {
      this.physicsHeat = 0
    }
    if (!this.physicsHeat && !this.dragNode) {
      return
    }

    if (this.physicsHeat > 0.045) {
      this.applyRepulsion(this.physicsHeat, dt)
    }

    if (this.physicsHeat > 0.028) {
      for (const edge of this.visibleEdgesCache) {
        const a = edge.A
        const b = edge.B
        const dx = b.x - a.x
        const dy = b.y - a.y
        const distance = Math.max(1, Math.hypot(dx, dy))
        const target = edge.type === 'cluster' ? 78 : 118
        const k = edge.type === 'cluster' ? 0.018 : 0.026
        const force = (distance - target) * k * this.physicsHeat
        const ux = dx / distance
        const uy = dy / distance
        if (a !== this.dragNode) {
          a.vx += ux * force * dt
          a.vy += uy * force * dt
        }
        if (b !== this.dragNode) {
          b.vx -= ux * force * dt
          b.vy -= uy * force * dt
        }
      }
    }


    for (const node of visible) {
      if (node === this.dragNode) {
        continue
      }
      const cluster = this.clusterById(node.cluster)
      // 弱中心力配合连线弹簧，不把选中邻居拉回预设圆环。
      node.vx += (cluster.x - node.x) * 0.0007 * this.physicsHeat * dt
      node.vy += (cluster.y - node.y) * 0.0007 * this.physicsHeat * dt
      const damp = this.dragNode ? DAMP_DRAG : DAMP
      node.vx *= Math.pow(damp, dt)
      node.vy *= Math.pow(damp, dt)
      if (Math.abs(node.vx) < 0.002) {
        node.vx = 0
      }
      if (Math.abs(node.vy) < 0.002) {
        node.vy = 0
      }
      node.x += node.vx * dt
      node.y += node.vy * dt
    }
  }

  private clusterById(id: string): Cluster {
    return this.clusters.find((c) => c.id === id) ?? { id, label: id, x: 0, y: 0, color: [140, 140, 160] }
  }

  /* ============================ 绘制 ============================ */

  private currentTrace() {
    return this.activeTraceKey ? this.tracePaths.get(this.activeTraceKey) : undefined
  }

  private activeTraceKey: string | null = null

  private nodeAlpha(node: SimNode): number {
    if (this.traceActive) {
      const trace = this.currentTrace()
      return trace?.nodeSet.has(node.id) ? 1 : 0.045
    }
    if (!this.selected) {
      return 1
    }
    if (node.id === this.selected.id) {
      return 1
    }
    const distance = this.focusDistCache?.get(node.id)
    return distance === 1 ? 0.92 : distance === 2 ? 0.38 : 0.05
  }

  private edgeAlpha(edge: SimEdge): number {
    if (this.traceActive) {
      const trace = this.currentTrace()
      const a = trace?.nodeSet.has(edge.a)
      const b = trace?.nodeSet.has(edge.b)
      if (a && b) {
        const names = trace!.names
        const ai = names.indexOf(edge.A.name)
        const bi = names.indexOf(edge.B.name)
        if ((ai >= 0 && bi === ai + 1) || (bi >= 0 && ai === bi + 1)) {
          return 0.86
        }
      }
      return 0.016
    }
    if (this.hoverNode && (edge.a === this.hoverNode.id || edge.b === this.hoverNode.id)) {
      return 0.62
    }
    if (this.selected) {
      const da = this.focusDistCache?.get(edge.a)
      const db = this.focusDistCache?.get(edge.b)
      if (da != null && db != null && Math.min(da, db) <= 1) {
        return 0.52
      }
      return 0.022
    }
    return edge.type === 'cluster' ? 0.20 : 0.32
  }

  private cubicControls(A: SimNode, B: SimNode, edge: SimEdge | null, wobble = 0) {
    const dx = B.sx - A.sx
    const dy = B.sy - A.sy
    const len = Math.max(1, Math.hypot(dx, dy))
    const nx = -dy / len
    const ny = dx / len
    const curve = (edge?.curve ?? 0) + wobble
    const bias = edge?.bias ?? 0
    const offset = len * curve
    return {
      c1: {
        x: A.sx + dx * (0.32 + bias * 0.05) + nx * offset * 0.72,
        y: A.sy + dy * (0.32 + bias * 0.05) + ny * offset * 0.72,
      },
      c2: {
        x: A.sx + dx * (0.7 + bias * 0.04) + nx * offset * 1.04,
        y: A.sy + dy * (0.7 + bias * 0.04) + ny * offset * 1.04,
      },
    }
  }

  private cubicPoint(A: SimNode, B: SimNode, edge: SimEdge | null, t: number, wobble = 0) {
    const { c1, c2 } = this.cubicControls(A, B, edge, wobble)
    const u = 1 - t
    return {
      x: u * u * u * A.sx + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * B.sx,
      y: u * u * u * A.sy + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * B.sy,
    }
  }

  private edgeOnScreen(edge: SimEdge, margin = 90): boolean {
    const a = edge.A
    const b = edge.B
    return !(
      (a.sx < -margin && b.sx < -margin) ||
      (a.sx > this.W + margin && b.sx > this.W + margin) ||
      (a.sy < -margin && b.sy < -margin) ||
      (a.sy > this.H + margin && b.sy > this.H + margin)
    )
  }

  private edgeLive(edge: SimEdge, time: number): boolean {
    if (this.hoverNode && (edge.a === this.hoverNode.id || edge.b === this.hoverNode.id)) {
      return true
    }
    return !!(
      this.selected &&
      time < this.lineMotionUntil &&
      (edge.a === this.selected.id || edge.b === this.selected.id)
    )
  }

  private drawEdge(edge: SimEdge, alpha: number, time: number): void {
    if (!this.edgeOnScreen(edge)) {
      return
    }
    const A = edge.A
    const B = edge.B
    const live = this.edgeLive(edge, time)
    const wobble = live ? Math.sin(time * 0.0023 + edge.phase) * 0.01 : 0
    const ctx = this.ctx
    ctx.globalAlpha = alpha
    ctx.strokeStyle = document.documentElement.dataset.theme === 'dark' ? 'rgb(183,187,207)' : '#526078'
    ctx.lineWidth = live ? 0.92 : 0.56
    ctx.beginPath()
    ctx.moveTo(A.sx, A.sy)
    ctx.lineTo(B.sx, B.sy)
    ctx.stroke()

    if (live && alpha > 0.12) {
      // 线上跑的一小段高亮：表示「这条边正相关」
      const t = (time * 0.00034 + edge.phase / (Math.PI * 2)) % 1
      const start = Math.max(0, t - 0.048)
      const end = Math.min(1, t + 0.048)
      ctx.globalAlpha = Math.min(0.62, alpha * 0.95)
      ctx.strokeStyle = document.documentElement.dataset.theme === 'dark' ? 'rgb(222,224,238)' : '#374151'
      ctx.lineWidth = 1.08
      ctx.beginPath()
      for (let i = 0; i < 5; i++) {
        const point = this.cubicPoint(A, B, edge, start + (end - start) * (i / 4), wobble)
        if (i) {
          ctx.lineTo(point.x, point.y)
        } else {
          ctx.moveTo(point.x, point.y)
        }
      }
      ctx.stroke()
    }
  }

  /** 节点用圆点表示，类型与源码信息放在详情里。 */
  private drawShape(node: SimNode, radius: number, alpha: number): void {
    const ctx = this.ctx
    const color = this.clusterById(node.cluster).color
    const isSelected = this.selected?.id === node.id
    const isHover = this.hoverNode?.id === node.id
    ctx.globalAlpha = alpha
    ctx.shadowBlur = 0

    if (isSelected || isHover) {
      ctx.strokeStyle = `rgba(${color.join(',')},${isSelected ? 0.3 : 0.15})`
      ctx.lineWidth = 0.8
      ctx.beginPath()
      ctx.arc(node.sx, node.sy, radius + (isSelected ? 7 : 5), 0, Math.PI * 2)
      ctx.stroke()
      ctx.shadowBlur = isSelected ? 9 : 4
      ctx.shadowColor = `rgba(${color.join(',')},${isSelected ? 0.34 : 0.2})`
    }

    const fill = isSelectionOnly(node, this.selected)
    ctx.fillStyle = `rgb(${color.join(',')})`
    ctx.strokeStyle = `rgba(${color.join(',')},.82)`
    ctx.lineWidth = 0.85
    ctx.beginPath()
    ctx.arc(node.sx, node.sy, radius, 0, Math.PI * 2)
    ctx.fill()

    if (fill) {
      // 推断出来的节点描一圈虚线：与「有源码证据」的实心节点区分开
      ctx.setLineDash([2, 2])
      ctx.strokeStyle = `rgba(${color.join(',')},.5)`
      ctx.beginPath()
      ctx.arc(node.sx, node.sy, radius + 2.5, 0, Math.PI * 2)
      ctx.stroke()
      ctx.setLineDash([])
    }
    ctx.shadowBlur = 0
  }

  /** 缩放决定标签密度：放大了才显示次要节点，避免糊成一片。 */
  private shouldLabel(node: SimNode): boolean {
    if (this.hoverNode?.id === node.id || this.selected?.id === node.id) {
      return true
    }
    if (this.selected) {
      const distance = this.focusDistCache?.get(node.id)
      return distance === 1 || (this.cam.scale > 1.42 && distance === 2)
    }
    if (this.cam.scale > 1.6) {
      return node.importance >= 2
    }
    if (this.cam.scale > 1.06) {
      return node.importance >= 4
    }
    return node.importance >= 5
  }

  private drawTrace(): void {
    if (!this.traceActive) {
      return
    }
    const trace = this.currentTrace()
    if (!trace) {
      return
    }
    const ctx = this.ctx
    ctx.shadowBlur = 0
    for (let i = 0; i < trace.segments.length; i++) {
      const segment = trace.segments[i]!
      const { c1, c2 } = this.cubicControls(segment.A, segment.B, segment)
      ctx.globalAlpha = 0.44
      ctx.strokeStyle = 'rgb(181,185,255)'
      ctx.lineWidth = 1
      ctx.beginPath()
      ctx.moveTo(segment.A.sx, segment.A.sy)
      ctx.bezierCurveTo(c1.x, c1.y, c2.x, c2.y, segment.B.sx, segment.B.sy)
      ctx.stroke()
      const t = (this.traceT + i * 0.12) % 1
      const point = this.cubicPoint(segment.A, segment.B, segment, t)
      ctx.globalAlpha = 0.94
      ctx.fillStyle = 'rgb(230,232,255)'
      ctx.beginPath()
      ctx.arc(point.x, point.y, 1.35, 0, Math.PI * 2)
      ctx.fill()
    }
  }

  /** 一帧。返回是否还需要下一帧 —— 静止时返回 false，交给调用方停掉 rAF。 */
  tick(time: number): boolean {
    const lineLive = !!this.hoverNode || (!!this.selected && time < this.lineMotionUntil)
    const moving = this.physicsHeat > HEAT_MIN || !!this.dragNode || this.cameraMoving()

    // 有持续动画（trace 光点 / 线高亮）时节流到 30fps；低配再降一档
    const capTrace = this.traceActive && !moving && !this.isPanning
    const capLine = lineLive && !moving && !this.traceActive
    const minInterval = capTrace ? (this.lowPower ? 41.7 : 33.3) : capLine ? (this.lowPower ? 50 : 33.3) : 0
    if (minInterval && time - this.lastPaint < minInterval) {
      return true
    }

    const started = performance.now()
    const dt = Math.max(0.1, Math.min(2, (time - this.lastTime) / 16.67 || 1))
    this.lastTime = time
    this.lastPaint = time

    const steps = Math.ceil(dt)
    for (let i = 0; i < steps; i++) this.applyPhysics(dt / steps)
    this.smoothCamera()
    this.updateScreenPositions()

    const ctx = this.ctx
    ctx.globalAlpha = 1
    ctx.fillStyle = document.documentElement.dataset.theme === 'dark' ? '#09090b' : '#ffffff'
    ctx.fillRect(0, 0, this.W, this.H)
    ctx.lineCap = 'round'

    for (const edge of this.visibleEdgesCache) {
      this.drawEdge(edge, this.edgeAlpha(edge), time)
    }
    this.drawTrace()

    ctx.font = '500 10.5px Inter,system-ui,sans-serif'
    ctx.textBaseline = 'alphabetic'
    for (const node of this.visibleCache) {
      if (node.sx < -80 || node.sx > this.W + 80 || node.sy < -80 || node.sy > this.H + 80) {
        continue
      }
      const alpha = this.nodeAlpha(node)
      const radius = (2.1 + node.importance * 0.54) * Math.min(Math.max(this.cam.scale * 0.84, 0.76), 1.36)
      this.drawShape(node, radius, alpha)
      if (this.shouldLabel(node)) {
        ctx.globalAlpha = alpha
        ctx.shadowBlur = 0
        ctx.fillStyle =
          this.hoverNode?.id === node.id || this.selected?.id === node.id
            ? (document.documentElement.dataset.theme === 'dark' ? 'rgba(242,243,247,.95)' : '#242733')
            : (document.documentElement.dataset.theme === 'dark' ? 'rgba(205,207,216,.68)' : '#596273')
        ctx.fillText(node.name, node.sx + radius + 6, node.sy + 3)
      }
    }
    ctx.globalAlpha = 1

    if (this.traceActive) {
      this.traceT = (this.traceT + 0.0104 * (capTrace ? 1 : 0.55)) % 1
    }

    const cost = performance.now() - started
    this.perf.frames++
    this.perf.avgDrawMs = this.perf.avgDrawMs ? this.perf.avgDrawMs * 0.94 + cost * 0.06 : cost

    // 画得太慢就降一次画质（只降一次，避免抖动）
    if (
      !this.adaptiveLowQuality &&
      this.dpr > 1 &&
      this.perf.frames > 30 &&
      this.perf.frames % 30 === 0 &&
      this.perf.avgDrawMs > 8
    ) {
      this.adaptiveLowQuality = true
      this.resize()
    }

    return this.traceActive || this.physicsHeat > HEAT_MIN || !!this.dragNode || this.cameraMoving() || lineLive
  }

  /* ============================ 交互 ============================ */

  hitTest(x: number, y: number): SimNode | null {
    let best: SimNode | null = null
    let bestDistance = Infinity
    for (const node of this.visibleCache) {
      const dx = x - node.sx
      const dy = y - node.sy
      const d2 = dx * dx + dy * dy
      const radius = 8 + node.importance * 0.85
      if (d2 < radius * radius && d2 < bestDistance) {
        best = node
        bestDistance = d2
      }
    }
    return best
  }

  pointerDown(x: number, y: number): 'node' | 'pan' {
    const node = this.hitTest(x, y)
    if (node) {
      this.dragNode = node
      this.dragState = this.buildDragState(node, this.screenToWorld(x, y))
      this.physicsHeat = 0.62
      this.requestFrame()
      return 'node'
    }
    this.isPanning = true
    return 'pan'
  }

  pointerMove(x: number, y: number, deltaFromLast: { x: number; y: number }): void {
    if (this.dragNode) {
      const world = this.screenToWorld(x, y)
      if (this.dragState) {
        const mdx = world.x - this.dragState.lastX
        const mdy = world.y - this.dragState.lastY
        this.dragState.ivx = this.dragState.ivx * 0.56 + mdx * 0.44
        this.dragState.ivy = this.dragState.ivy * 0.56 + mdy * 0.44
        this.dragState.dx = world.x - this.dragState.originX
        this.dragState.dy = world.y - this.dragState.originY
        this.dragState.lastX = world.x
        this.dragState.lastY = world.y
      }
      this.dragNode.x = world.x
      this.dragNode.y = world.y
      this.dragNode.vx = 0
      this.dragNode.vy = 0
      this.physicsHeat = 0.58
      this.requestFrame()
      return
    }
    if (this.isPanning) {
      this.cam.x += deltaFromLast.x / this.cam.scale
      this.cam.y += deltaFromLast.y / this.cam.scale
      this.camTarget.x = this.cam.x
      this.camTarget.y = this.cam.y
      this.requestFrame()
      return
    }
    const hover = this.hitTest(x, y)
    if (hover !== this.hoverNode) {
      this.hoverNode = hover
      this.requestFrame()
    }
  }

  /** 松手：把拖拽速度按权重传给邻居 —— 弹性回稳的手感来源。 */
  pointerUp(): void {
    if (this.dragNode) {
      if (this.dragState) {
        for (const item of this.dragState.influence) {
          item.n.vx += this.dragState.ivx * item.weight * 0.32
          item.n.vy += this.dragState.ivy * item.weight * 0.32
        }
      }
      this.dragNode = null
      this.dragState = null
      this.physicsHeat = 0.65
      this.lineMotionUntil = performance.now() + 700
      this.requestFrame()
    }
    this.isPanning = false
  }

  wheel(deltaY: number, offsetX: number, offsetY: number): void {
    const old = this.camTarget.scale
    const factor = deltaY < 0 ? 1.095 : 0.915
    const next = Math.max(0.34, Math.min(2.6, old * factor))
    // 以光标为锚点缩放
    const mx = offsetX - this.W / 2
    const my = offsetY - this.H / 2
    this.camTarget.x -= mx / old - mx / next
    this.camTarget.y -= my / old - my / next
    this.camTarget.scale = next
    this.requestFrame()
  }

  selectNode(node: SimNode, zoom = 1.28): void {
    this.selected = node
    this.traceActive = false
    this.lineMotionUntil = performance.now() + 1100
    this.physicsHeat = 0.4
    this.rebuildState()
    this.focusCamera(node, zoom)
  }

  selectByKey(key: string, zoom = 1.28): boolean {
    const node = this.nodeById.get(key)
    if (!node) {
      return false
    }
    this.selectNode(node, zoom)
    return true
  }

  clearSelection(): void {
    this.selected = null
    this.traceActive = false
    this.physicsHeat = 0.2
    this.rebuildState()
    this.fit()
  }

  focusSelected(): void {
    if (!this.selected) {
      return
    }
    this.lineMotionUntil = performance.now() + 1000
    this.focusCamera(this.selected, 1.45)
    this.physicsHeat = 0.28
    this.requestFrame()
  }

  setTrace(key: string | null): void {
    if (!key) {
      this.traceActive = false
      this.activeTraceKey = null
      this.selected = null
      this.physicsHeat = 0.2
      this.rebuildState()
      this.fit()
      this.requestFrame()
      return
    }
    if (!this.tracePaths.has(key)) {
      return
    }
    this.activeTraceKey = key
    this.traceActive = true
    this.selected = this.nodeById.get(key) ?? null
    this.physicsHeat = 0.28
    this.rebuildState()
    if (this.selected) {
      this.focusCamera(this.selected, 1.05)
    }
    this.requestFrame()
  }

  isTraceActive(): boolean {
    return this.traceActive
  }

  getSelected(): SimNode | null {
    return this.selected
  }

  search(query: string, limit = 7): GraphSearchHit[] {
    const text = query.trim().toLowerCase()
    if (!text) {
      return []
    }
    const found: GraphSearchHit[] = []
    for (const node of this.nodes) {
      if (node.name.toLowerCase().includes(text) || node.path.toLowerCase().includes(text)) {
        found.push({ key: node.id, name: node.name, kind: node.kind })
        if (found.length === limit) {
          break
        }
      }
    }
    return found
  }

  /** 抽屉要展示的信息：节点本身 + 它的出入边。 */
  describe(key: string): DrawerInfo | null {
    const node = this.nodeById.get(key)
    if (!node) {
      return null
    }
    const neighbors = Array.from(this.adj.get(node.id) ?? [])
      .map((id) => this.nodeById.get(id)!)
      .filter(Boolean)
    const outgoing = this.edges.filter((e) => e.a === node.id).length
    const incoming = this.edges.filter((e) => e.b === node.id).length
    return {
      kind: node.kind.toUpperCase(),
      name: node.name,
      path: node.path,
      evidence: node.evidence,
      summary: node.summary,
      incoming,
      outgoing,
      dependsOn: neighbors.slice(0, 4).map((n) => n.name),
      usedBy: neighbors.slice(4, 8).map((n) => n.name),
      sourceRef: node.path,
    }
  }

  stats(): GraphStats {
    return {
      lowPower: this.lowPower,
      adaptiveLowQuality: this.adaptiveLowQuality,
      dpr: this.dpr,
      visible: this.visibleCache.length,
      edges: this.visibleEdgesCache.length,
      frames: this.perf.frames,
      avgDrawMs: Number(this.perf.avgDrawMs.toFixed(3)),
      animating: false,
      heat: Number(this.physicsHeat.toFixed(4)),
    }
  }

  /** 集群标签：DOM 元素由组件渲染，这里只给出位置与透明度。 */
  clusterLabelState(): Array<{ id: string; label: string; x: number; y: number; opacity: number }> {
    if (!this.visibleCache.length) {
      return []
    }
    return this.clusters
      .map((cluster) => {
        const members = this.clusterMembersVisible.get(cluster.id) ?? []
        if (!members.length) {
          return null
        }
        let x = 0
        let y = 0
        for (const node of members) {
          x += node.x
          y += node.y
        }
        x /= members.length
        y /= members.length
        const point = { x: this.W / 2 + (x + this.cam.x) * this.cam.scale, y: this.H / 2 + (y + this.cam.y) * this.cam.scale }
        return {
          id: cluster.id,
          label: cluster.label,
          x: point.x,
          y: point.y,
          opacity: this.selected?.cluster === cluster.id ? 0.78 : this.mode === 'global' ? 0.72 : 0.38,
        }
      })
      .filter((item): item is { id: string; label: string; x: number; y: number; opacity: number } => item !== null)
  }

  nodeCount(): number {
    return this.nodes.length
  }

  private requestFrame(): void {
    this.onNeedsFrame?.()
  }
}

/** 由字符串派生 [0,1) 的稳定伪随机：同一张图每次布局一致。 */
function hashUnit(seed: string): number {
  let hash = 2166136261
  for (let i = 0; i < seed.length; i++) {
    hash ^= seed.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return ((hash >>> 0) % 100000) / 100000
}

function isSelectionOnly(node: SimNode, selected: SimNode | null): boolean {
  return node.evidence !== 'CODE_EVIDENCE' && (!selected || node.id !== selected.id)
}
