export type VisualSelectedElementInfo = {
  tagName: string
  id?: string
  className?: string
  name?: string
  role?: string
  type?: string
  text?: string
  ariaLabel?: string
  placeholder?: string
  href?: string
  src?: string
  cssSelector?: string
  xpath?: string
  /**
   * 元素在 iframe 视口内的几何信息（用于父页面绘制高亮覆盖层）
   */
  rect?: {
    top: number
    left: number
    width: number
    height: number
  }
}

export type VisualEditorAttachResult = {
  detach: () => void
}

type InjectedApi = {
  attach: (opts: {
    hoverColor: string
    selectedColor: string
    selectedWidth: number
    hoverWidth: number
    messageType: string
    hoverMessageType: string
    parentOrigin: string
  }) => void
  detach: () => void
  /** 清除选中/悬浮高亮（SPA 内路由切换时由父页面调用） */
  clearSelection: () => void
}

const DEFAULT_MESSAGE_TYPE = 'glyahh_visual_editor:selected'
const DEFAULT_HOVER_MESSAGE_TYPE = 'glyahh_visual_editor:hover'
const HOVER_CLASS = 'glyahh-edit-hover'
const SELECTED_CLASS = 'glyahh-edit-selected'
const TIP_ID = '__glyahh_visual_editor_tip__'
const STYLE_ID = '__glyahh_visual_editor_styles__'
const MODE_CLASS = '__glyahh_visual_editor_mode__'
const INJECTED_API_VERSION = 6

/** iframe 内：父页面合成“穿透”事件时跳过选中逻辑，避免拦截路由/链接 */
const WIN_PASS_THROUGH = '__GLYAHH_VISUAL_PASS_THROUGH__'

function safeTextPreview(s: unknown, maxLen = 140): string {
  const t = String(s ?? '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!t) return ''
  return t.length > maxLen ? `${t.slice(0, maxLen)}…` : t
}

function getAttr(el: Element, name: string): string | undefined {
  const v = el.getAttribute(name)
  return v != null && String(v).trim() ? String(v).trim() : undefined
}

function buildCssSelector(el: Element): string | undefined {
  try {
    // 优先使用 id（唯一性通常最好）
    const id = (el as HTMLElement).id
    if (id && /^[A-Za-z][\w\-:.]*$/.test(id)) return `#${id}`

    const parts: string[] = []
    let cur: Element | null = el
    let depth = 0
    while (cur && depth < 6) {
      const tag = cur.tagName.toLowerCase()
      if (tag === 'html') break

      let part = tag
      const cid = (cur as HTMLElement).id
      if (cid && /^[A-Za-z][\w\-:.]*$/.test(cid)) {
        part += `#${cid}`
        parts.unshift(part)
        break
      }

      const classList = Array.from((cur as HTMLElement).classList || [])
        .filter(Boolean)
        .slice(0, 2)
      if (classList.length) {
        part += classList.map((c) => (c && /^[A-Za-z_][\w-]*$/.test(c) ? `.${c}` : '')).join('')
      }

      // 若同级同 tag 重复，附加 nth-of-type
      const parent = cur.parentElement
      if (parent) {
        const siblingsSameTag = Array.from(parent.children).filter(
          (c) => (c as Element).tagName.toLowerCase() === tag,
        )
        if (siblingsSameTag.length > 1) {
          const idx = siblingsSameTag.indexOf(cur) + 1
          if (idx > 0) part += `:nth-of-type(${idx})`
        }
      }

      parts.unshift(part)
      cur = cur.parentElement
      depth += 1
    }

    if (!parts.length) return undefined
    return parts.join(' > ')
  } catch {
    return undefined
  }
}

function buildXpath(el: Element): string | undefined {
  try {
    const segments: string[] = []
    let cur: Element | null = el
    let depth = 0
    while (cur && depth < 10) {
      const tag = cur.tagName.toLowerCase()
      if (tag === 'html') {
        segments.unshift('/html')
        break
      }
      if (tag === 'body') {
        segments.unshift('/body')
        break
      }

      const parentEl: Element | null = cur.parentElement
      if (!parentEl) break
      const siblings = Array.from(parentEl.children).filter(
        (c) => (c as Element).tagName.toLowerCase() === tag,
      )
      const idx = siblings.indexOf(cur) + 1
      segments.unshift(`/${tag}[${Math.max(idx, 1)}]`)
      cur = parentEl
      depth += 1
    }
    const xp = segments.join('')
    return xp || undefined
  } catch {
    return undefined
  }
}

function extractElementInfo(el: Element): VisualSelectedElementInfo {
  const tagName = el.tagName.toLowerCase()
  const id = (el as HTMLElement).id || undefined
  const className = (el as HTMLElement).className
    ? String((el as HTMLElement).className)
        .trim()
        .slice(0, 160)
    : undefined
  const name = getAttr(el, 'name')
  const role = getAttr(el, 'role')
  const type = getAttr(el, 'type')
  const ariaLabel = getAttr(el, 'aria-label')
  const placeholder = getAttr(el, 'placeholder')
  const href = getAttr(el, 'href')
  const src = getAttr(el, 'src')

  const text = safeTextPreview(
    (el as HTMLElement).innerText || (el as HTMLElement).textContent || '',
  )
  const cssSelector = buildCssSelector(el)
  const xpath = buildXpath(el)
  let rect: VisualSelectedElementInfo['rect'] | undefined
  try {
    const r = (el as HTMLElement).getBoundingClientRect?.()
    if (r) {
      rect = {
        top: r.top,
        left: r.left,
        width: r.width,
        height: r.height,
      }
    }
  } catch {
    // ignore
  }

  return {
    tagName,
    id,
    className,
    name,
    role,
    type,
    text,
    ariaLabel,
    placeholder,
    href,
    src,
    cssSelector,
    xpath,
    rect,
  }
}

function ensureInjectedApi(win: Window): InjectedApi {
  const anyWin = win as any
  const existing = anyWin.__GLYAHH_VISUAL_EDITOR__
  if (existing && typeof existing.attach === 'function') {
    // 若版本一致直接复用；否则强制重建，避免旧实现残留导致“已注入但效果不更新”
    if (existing.__version === INJECTED_API_VERSION) return existing as InjectedApi
    try {
      existing.detach?.()
    } catch {
      // ignore
    }
  }

  const api: InjectedApi = (() => {
    function injectStyles(
      doc: Document,
      hoverColor: string,
      selectedColor: string,
      hoverWidth: number,
      selectedWidth: number,
    ) {
      const existing = doc.getElementById(STYLE_ID)
      if (existing) {
        // 更新颜色/粗细（便于后续配置）
        existing.textContent = buildCss(hoverColor, selectedColor, hoverWidth, selectedWidth)
        return
      }
      const styleEl = doc.createElement('style')
      styleEl.id = STYLE_ID
      ;(styleEl as HTMLElement).dataset.glyahhVisualEditor = 'true'
      styleEl.textContent = buildCss(hoverColor, selectedColor, hoverWidth, selectedWidth)
      doc.head?.appendChild(styleEl)
    }

    function buildCss(
      hoverColor: string,
      selectedColor: string,
      hoverWidth: number,
      selectedWidth: number,
    ) {
      return `
        html, body { margin: 0 !important; padding: 0 !important; width: 100%; height: 100%; }
        body { box-sizing: border-box; }
        canvas { display: block; }

        /* 编辑模式下禁止选中文本，避免误以为没生效（其实在选字） */
        html.${MODE_CLASS}, html.${MODE_CLASS} * { user-select: none !important; -webkit-user-select: none !important; }

        .${HOVER_CLASS} {
          outline: ${hoverWidth}px dashed ${hoverColor} !important;
          outline-offset: 2px !important;
          cursor: crosshair !important;
          transition: outline 0.18s ease !important;
          position: relative !important;
        }

        .${SELECTED_CLASS} {
          outline: ${selectedWidth}px solid ${selectedColor} !important;
          outline-offset: 2px !important;
          cursor: default !important;
          position: relative !important;
        }
      `
    }

    function showTip(doc: Document) {
      try {
        if (doc.getElementById(TIP_ID)) return
        const tip = doc.createElement('div')
        tip.id = TIP_ID
        tip.textContent = '编辑模式：单击选中元素；双击进入子页面/链接（仍可继续选中）'
        ;(tip as HTMLElement).dataset.glyahhVisualEditor = 'true'
        tip.setAttribute(
          'style',
          [
            'position:fixed',
            'top:16px',
            'right:16px',
            'background:rgba(24,144,255,0.95)',
            'color:#fff',
            'padding:10px 12px',
            'border-radius:8px',
            'font-size:13px',
            'line-height:1.35',
            'z-index:2147483647',
            'box-shadow:0 10px 22px rgba(15, 23, 42, 0.25)',
          ].join(';'),
        )
        doc.body?.appendChild(tip)
        win.setTimeout(() => {
          try {
            tip.remove()
          } catch {
            // ignore
          }
        }, 2400)
      } catch {
        // ignore
      }
    }

    let attached = false
    let lastHover: Element | null = null
    let lastSelected: Element | null = null
    let hoverColor = 'rgba(14, 165, 233, 0.9)'
    let selectedColor = 'rgba(37, 99, 235, 0.98)'
    let selectedWidth = 3
    let hoverWidth = 2
    let messageType = DEFAULT_MESSAGE_TYPE
    let hoverMessageType = DEFAULT_HOVER_MESSAGE_TYPE
    let parentOrigin = '*'

    let hoverBox: HTMLDivElement | null = null
    let selectedBox: HTMLDivElement | null = null
    let rafId: number | null = null
    let lastHoverSentAt = 0

    function ensureOverlayBoxes(doc: Document) {
      if (!doc.body) return
      if (!hoverBox) {
        hoverBox = doc.createElement('div')
        ;(hoverBox as HTMLElement).dataset.glyahhVisualEditor = 'true'
        hoverBox.style.cssText = [
          'position:fixed',
          'pointer-events:none',
          'z-index:2147483646',
          'display:none',
          'box-sizing:border-box',
          'border-radius:2px',
          'background:rgba(24,144,255,0.06)',
          'box-shadow:0 10px 22px rgba(15,23,42,0.22)',
        ].join(';')
        doc.body.appendChild(hoverBox)
      }
      if (!selectedBox) {
        selectedBox = doc.createElement('div')
        ;(selectedBox as HTMLElement).dataset.glyahhVisualEditor = 'true'
        selectedBox.style.cssText = [
          'position:fixed',
          'pointer-events:none',
          'z-index:2147483647',
          'display:none',
          'box-sizing:border-box',
          'border-radius:2px',
          'background:rgba(82,196,26,0.06)',
          'box-shadow:0 12px 26px rgba(15,23,42,0.24)',
        ].join(';')
        doc.body.appendChild(selectedBox)
      }
    }

    function updateBoxForEl(box: HTMLDivElement | null, el: Element | null, borderCss: string) {
      if (!box) return
      if (!el) {
        box.style.display = 'none'
        return
      }
      const r = (el as HTMLElement).getBoundingClientRect?.()
      if (!r) {
        box.style.display = 'none'
        return
      }
      // 过滤无尺寸元素，避免闪烁
      if (r.width <= 0 || r.height <= 0) {
        box.style.display = 'none'
        return
      }
      box.style.display = 'block'
      box.style.left = `${Math.round(r.left)}px`
      box.style.top = `${Math.round(r.top)}px`
      box.style.width = `${Math.round(r.width)}px`
      box.style.height = `${Math.round(r.height)}px`
      box.style.border = borderCss
    }

    function scheduleOverlayUpdate() {
      if (rafId != null) return
      rafId = win.requestAnimationFrame(() => {
        rafId = null
        updateBoxForEl(
          hoverBox,
          lastHover && lastHover !== lastSelected ? lastHover : null,
          `${hoverWidth}px dashed ${hoverColor}`,
        )
        updateBoxForEl(selectedBox, lastSelected, `${selectedWidth}px solid ${selectedColor}`)
      })
    }

    function setHoverTarget(el: Element | null) {
      if (!el || !(el instanceof Element) || !isInteractiveTarget(el)) {
        if (lastHover && lastHover !== lastSelected) {
          ;(lastHover as HTMLElement).classList.remove(HOVER_CLASS)
        }
        lastHover = null
        scheduleOverlayUpdate()
        return
      }
      if (lastHover === el) return
      if (lastHover && lastHover !== lastSelected) {
        ;(lastHover as HTMLElement).classList.remove(HOVER_CLASS)
      }
      lastHover = el
      if (lastHover && lastHover !== lastSelected) {
        ;(lastHover as HTMLElement).classList.add(HOVER_CLASS)
        scheduleOverlayUpdate()
        // 限流发送 hover 信息（避免 mousemove 过于频繁）
        const now = Date.now()
        if (now - lastHoverSentAt > 120) {
          lastHoverSentAt = now
          try {
            const payload = extractElementInfo(lastHover)
            win.parent?.postMessage({ type: hoverMessageType, payload }, parentOrigin || '*')
          } catch {
            // ignore
          }
        }
      }
    }

    function isInteractiveTarget(el: Element): boolean {
      const tag = el.tagName.toLowerCase()
      if (tag === 'html' || tag === 'body') return false
      if (tag === 'script' || tag === 'style') return false
      // 过滤掉我们自己注入的标记（避免误选中）
      if ((el as HTMLElement).dataset && (el as HTMLElement).dataset.glyahhVisualEditor === 'true')
        return false
      return true
    }

    function onMouseOver(e: Event) {
      if (!attached) return
      const target = e.target as Element | null
      if (!target || !(target instanceof Element)) return
      if (!isInteractiveTarget(target)) return

      setHoverTarget(target)
    }

    function onMouseOut() {
      if (!attached) return
      setHoverTarget(null)
    }

    function onClick(e: MouseEvent) {
      if (!attached) return
      try {
        if ((win as any)[WIN_PASS_THROUGH]) return
      } catch {
        // ignore
      }
      const target = e.target as Element | null
      if (!target || !(target instanceof Element)) return
      if (!isInteractiveTarget(target)) return

      // 阻止站点自身点击行为（编辑模式下以“选择元素”为主）
      e.preventDefault()
      e.stopPropagation()

      if (lastSelected) {
        ;(lastSelected as HTMLElement).classList.remove(SELECTED_CLASS)
      }
      lastSelected = target
      ;(lastSelected as HTMLElement).classList.remove(HOVER_CLASS)
      ;(lastSelected as HTMLElement).classList.add(SELECTED_CLASS)
      scheduleOverlayUpdate()

      try {
        const payload = extractElementInfo(target)
        win.parent?.postMessage({ type: messageType, payload }, parentOrigin || '*')
      } catch {
        // ignore
      }
    }

    function onDblClick(e: MouseEvent) {
      if (!attached) return
      try {
        if ((win as any)[WIN_PASS_THROUGH]) return
      } catch {
        // ignore
      }
      // 无 shield 覆盖时（少见）：双击不拦截，交给页面处理导航
      const target = e.target as Element | null
      if (!target || !(target instanceof Element)) return
      if (!isInteractiveTarget(target)) return
    }

    function onScrollOrResize() {
      if (!attached) return
      scheduleOverlayUpdate()
    }

    function onMouseMove(e: MouseEvent) {
      if (!attached) return
      try {
        const el = win.document.elementFromPoint(e.clientX, e.clientY)
        setHoverTarget(el as any)
      } catch {
        // ignore
      }
    }

    function attach(opts: {
      hoverColor: string
      selectedColor: string
      selectedWidth: number
      hoverWidth: number
      messageType: string
      hoverMessageType: string
      parentOrigin: string
    }) {
      if (attached) return
      hoverColor = opts.hoverColor
      selectedColor = opts.selectedColor
      selectedWidth = opts.selectedWidth
      hoverWidth = opts.hoverWidth
      messageType = opts.messageType || DEFAULT_MESSAGE_TYPE
      hoverMessageType = opts.hoverMessageType || DEFAULT_HOVER_MESSAGE_TYPE
      parentOrigin = opts.parentOrigin || '*'

      attached = true
      injectStyles(win.document, hoverColor, selectedColor, hoverWidth, selectedWidth)
      try {
        win.document.documentElement?.classList.add(MODE_CLASS)
      } catch {
        // ignore
      }
      ensureOverlayBoxes(win.document)
      showTip(win.document)
      // 用 body 捕获，避免某些页面 document 上事件被特殊处理
      const root = win.document.body || win.document.documentElement || win.document
      root.addEventListener('mouseover', onMouseOver, true)
      root.addEventListener('mouseout', onMouseOut, true)
      root.addEventListener('click', onClick, true)
      root.addEventListener('dblclick', onDblClick, true)
      root.addEventListener('mousemove', onMouseMove, true)
      win.addEventListener('scroll', onScrollOrResize, true)
      win.addEventListener('resize', onScrollOrResize, true)
      scheduleOverlayUpdate()
    }

    function clearSelection() {
      if (!attached) return
      if (lastHover && lastHover !== lastSelected) {
        ;(lastHover as HTMLElement).classList.remove(HOVER_CLASS)
      }
      if (lastSelected) {
        ;(lastSelected as HTMLElement).classList.remove(SELECTED_CLASS)
      }
      lastHover = null
      lastSelected = null
      if (hoverBox) hoverBox.style.display = 'none'
      if (selectedBox) selectedBox.style.display = 'none'
      if (rafId != null) {
        try {
          win.cancelAnimationFrame(rafId)
        } catch {
          // ignore
        }
        rafId = null
      }
      scheduleOverlayUpdate()
    }

    function detach() {
      if (!attached) return
      attached = false
      const root = win.document.body || win.document.documentElement || win.document
      root.removeEventListener('mouseover', onMouseOver, true)
      root.removeEventListener('mouseout', onMouseOut, true)
      root.removeEventListener('click', onClick, true)
      root.removeEventListener('dblclick', onDblClick, true)
      root.removeEventListener('mousemove', onMouseMove, true)
      win.removeEventListener('scroll', onScrollOrResize, true)
      win.removeEventListener('resize', onScrollOrResize, true)
      try {
        win.document.documentElement?.classList.remove(MODE_CLASS)
      } catch {
        // ignore
      }
      if (lastHover && lastHover !== lastSelected) {
        ;(lastHover as HTMLElement).classList.remove(HOVER_CLASS)
      }
      if (lastSelected) {
        ;(lastSelected as HTMLElement).classList.remove(SELECTED_CLASS)
      }
      lastHover = null
      lastSelected = null
      if (hoverBox) hoverBox.style.display = 'none'
      if (selectedBox) selectedBox.style.display = 'none'
      if (rafId != null) {
        try {
          win.cancelAnimationFrame(rafId)
        } catch {
          // ignore
        }
        rafId = null
      }
    }

    return { attach, detach, clearSelection }
  })()

  ;(api as any).__version = INJECTED_API_VERSION
  anyWin.__GLYAHH_VISUAL_EDITOR__ = api
  return api
}

export function attachVisualWebsiteEditor(opts: {
  iframeEl: HTMLIFrameElement
  onSelected: (info: VisualSelectedElementInfo) => void
  onHover?: (info: VisualSelectedElementInfo) => void
  /** iframe 内路由/页面变化（如双击进入子页面）时清空父页选中态与提示 */
  onNavigateClear?: () => void
  onError?: (err: Error) => void
  messageType?: string
  hoverMessageType?: string
}): VisualEditorAttachResult {
  const { iframeEl, onSelected } = opts
  const messageType = opts.messageType || DEFAULT_MESSAGE_TYPE
  const hoverMessageType = opts.hoverMessageType || DEFAULT_HOVER_MESSAGE_TYPE

  let injected: InjectedApi | null = null
  let detached = false
  let iframeLoadListener: (() => void) | null = null
  let retryTimer: number | null = null
  let parentHoverBox: HTMLDivElement | null = null
  let parentSelectedBox: HTMLDivElement | null = null
  let parentShield: HTMLElement | null = null
  let parentScrollListener: (() => void) | null = null
  let parentIframePointerListener: (() => void) | null = null
  let parentWindowPointerListener: (() => void) | null = null
  let parentShieldWheelListener: ((ev: WheelEvent) => void) | null = null
  let lastHoverRect: VisualSelectedElementInfo['rect'] | null = null
  let lastSelectedRect: VisualSelectedElementInfo['rect'] | null = null

  function ensureParentBoxes() {
    if (parentHoverBox && parentSelectedBox) return
    const mk = (z: number, border: string, bg: string) => {
      const el = document.createElement('div')
      el.setAttribute('data-glyahh-visual-editor-parent', 'true')
      el.style.cssText = [
        'position:fixed',
        'pointer-events:none',
        `z-index:${z}`,
        'display:none',
        'box-sizing:border-box',
        'border-radius:2px',
        `border:${border}`,
        `background:${bg}`,
        'box-shadow:0 14px 32px rgba(15,23,42,0.28)',
      ].join(';')
      document.body.appendChild(el)
      return el
    }
    parentHoverBox = mk(2147483646, '2px dashed rgba(14,165,233,0.95)', 'rgba(14,165,233,0.06)')
    parentSelectedBox = mk(2147483647, '3px solid rgba(37,99,235,0.98)', 'rgba(37,99,235,0.05)')
  }

  function ensureParentShield() {
    if (parentShield) return
    const el = document.createElement('div')
    el.setAttribute('data-glyahh-visual-editor-parent-shield', 'true')
    el.style.cssText = [
      'position:fixed',
      'left:0',
      'top:0',
      'width:0',
      'height:0',
      'z-index:2147483645',
      'background:transparent',
      // 编辑模式下我们需要捕获鼠标移动/点击来做“选择元素”，所以必须接管事件
      'pointer-events:auto',
      'touch-action:none',
    ].join(';')
    document.body.appendChild(el)
    parentShield = el
  }

  function syncParentShield() {
    if (!parentShield) return
    const r = opts.iframeEl.getBoundingClientRect()
    parentShield.style.left = `${Math.round(r.left)}px`
    parentShield.style.top = `${Math.round(r.top)}px`
    parentShield.style.width = `${Math.round(r.width)}px`
    parentShield.style.height = `${Math.round(r.height)}px`
  }

  function hideParentBox(box: HTMLDivElement | null) {
    if (!box) return
    box.style.display = 'none'
  }

  function updateParentBox(
    box: HTMLDivElement | null,
    rect: VisualSelectedElementInfo['rect'] | null,
  ) {
    if (!box) return
    if (!rect) {
      box.style.display = 'none'
      return
    }
    const iframeRect = opts.iframeEl.getBoundingClientRect()
    // rect 是 iframe 视口坐标；换算到父页面视口坐标
    const left = iframeRect.left + rect.left
    const top = iframeRect.top + rect.top
    const width = rect.width
    const height = rect.height
    if (width <= 0 || height <= 0) {
      box.style.display = 'none'
      return
    }
    box.style.display = 'block'
    box.style.left = `${Math.round(left)}px`
    box.style.top = `${Math.round(top)}px`
    box.style.width = `${Math.round(width)}px`
    box.style.height = `${Math.round(height)}px`
  }

  function syncParentBoxes() {
    ensureParentBoxes()
    updateParentBox(parentHoverBox, lastHoverRect)
    updateParentBox(parentSelectedBox, lastSelectedRect)
  }

  function isParentInteractiveTarget(el: Element): boolean {
    try {
      const tag = el.tagName.toLowerCase()
      if (tag === 'html' || tag === 'body' || tag === 'script' || tag === 'style') return false
      // 避免把我们注入的节点当成目标
      if ((el as HTMLElement).dataset && (el as HTMLElement).dataset.glyahhVisualEditor === 'true')
        return false
      return true
    } catch {
      return false
    }
  }

  function pickElementFromIframe(clientX: number, clientY: number): Element | null {
    const iframeRect = opts.iframeEl.getBoundingClientRect()
    const x = clientX - iframeRect.left
    const y = clientY - iframeRect.top
    if (x < 0 || y < 0 || x > iframeRect.width || y > iframeRect.height) return null
    try {
      const doc = opts.iframeEl.contentDocument
      if (!doc) return null
      const el = doc.elementFromPoint(x, y) as Element | null
      return el && isParentInteractiveTarget(el) ? el : null
    } catch {
      return null
    }
  }

  function setHoverFromIframePoint(clientX: number, clientY: number) {
    const el = pickElementFromIframe(clientX, clientY)
    if (!el) {
      lastHoverRect = null
      syncParentBoxes()
      return
    }
    const info = extractElementInfo(el)
    lastHoverRect = info.rect || null
    syncParentBoxes()
    try {
      opts.onHover?.(info)
    } catch {
      // ignore
    }
  }

  function setSelectedFromIframePoint(clientX: number, clientY: number) {
    const el = pickElementFromIframe(clientX, clientY)
    if (!el) return
    const info = extractElementInfo(el)
    lastSelectedRect = info.rect || null
    lastHoverRect = null
    syncParentBoxes()
    try {
      opts.onSelected(info)
    } catch {
      // ignore
    }
  }

  function handleMessage(e: MessageEvent) {
    if (detached) return
    if (e.origin !== window.location.origin) return
    const data = e.data as any
    if (!data) return
    if (data.type === messageType && data.payload) {
      const info = data.payload as VisualSelectedElementInfo
      onSelected(info)
      lastSelectedRect = info.rect || null
      // 选中后清除 hover 框（体验更像“锁定”）
      lastHoverRect = null
      try {
        syncParentBoxes()
      } catch {
        // ignore
      }
      return
    }
    if (opts.onHover && data.type === hoverMessageType && data.payload) {
      const info = data.payload as VisualSelectedElementInfo
      opts.onHover(info)
      lastHoverRect = info.rect || null
      try {
        syncParentBoxes()
      } catch {
        // ignore
      }
      return
    }
  }

  window.addEventListener('message', handleMessage)
  // 父页面覆盖层：在滚动/缩放时保持对齐
  const onParentScrollResize = () => {
    if (detached) return
    try {
      syncParentBoxes()
      syncParentShield()
    } catch {
      // ignore
    }
  }
  window.addEventListener('scroll', onParentScrollResize, true)
  window.addEventListener('resize', onParentScrollResize, true)
  parentScrollListener = () => {
    window.removeEventListener('scroll', onParentScrollResize, true)
    window.removeEventListener('resize', onParentScrollResize, true)
  }
  // 先创建一次（即使子页视觉层完全不可见，父页也能画出来）
  try {
    ensureParentBoxes()
    ensureParentShield()
    syncParentShield()
  } catch {
    // ignore
  }

  // 最稳方案：父页面在 iframe 上方铺一层透明 shield，专门捕获 pointermove/click 用于“选择元素”
  // 好处：不依赖 iframe/浏览器对 mousemove 分发的差异，hover 一定触发；同时编辑模式下本来就应阻止站点交互。
  const onShieldPointerMove = (ev: PointerEvent) => {
    if (detached) return
    setHoverFromIframePoint(ev.clientX, ev.clientY)
  }
  const onShieldPointerLeave = () => {
    if (detached) return
    lastHoverRect = null
    syncParentBoxes()
  }
  const onShieldClick = (ev: MouseEvent) => {
    if (detached) return
    ev.preventDefault()
    ev.stopPropagation()
    // 双击序列的第二次 click（detail===2）不更新选中，交给 onShieldDblClick 做路由穿透
    if (ev.detail === 2) return
    setSelectedFromIframePoint(ev.clientX, ev.clientY)
  }

  /**
   * 双击：向 iframe 内目标元素派发真实鼠标序列，使 Vue Router / <a> 等能导航；
   * 通过 WIN_PASS_THROUGH 跳过注入脚本的 preventDefault，避免被“只选中不导航”拦截。
   */
  const onShieldDblClick = (ev: MouseEvent) => {
    if (detached) return
    ev.preventDefault()
    ev.stopPropagation()
    const el = pickElementFromIframe(ev.clientX, ev.clientY)
    if (!el) return
    // 点到文字节点内的子元素时，优先对可导航的 <a> 等派发，便于 Vue Router / 原生链接
    let dispatchEl: Element = el
    try {
      const link = el.closest?.('a[href]') as Element | null
      if (link) dispatchEl = link
    } catch {
      // ignore
    }
    const innerWin = iframeEl.contentWindow
    if (!innerWin) return
    const iframeRect = iframeEl.getBoundingClientRect()
    const ix = ev.clientX - iframeRect.left
    const iy = ev.clientY - iframeRect.top
    const common: MouseEventInit = {
      bubbles: true,
      cancelable: true,
      clientX: ix,
      clientY: iy,
      view: innerWin,
      button: 0,
    }
    try {
      ;(innerWin as any)[WIN_PASS_THROUGH] = true
      // 与「用户双击」对应：在子页面触发一次完整点击，供 Vue Router / <a> / @click 导航（避免重复触发两次路由）
      dispatchEl.dispatchEvent(new MouseEvent('mousedown', common))
      dispatchEl.dispatchEvent(new MouseEvent('mouseup', common))
      dispatchEl.dispatchEvent(new MouseEvent('click', { ...common, detail: 1 }))
    } catch {
      // ignore
    } finally {
      try {
        delete (innerWin as any)[WIN_PASS_THROUGH]
      } catch {
        ;(innerWin as any)[WIN_PASS_THROUGH] = false
      }
    }
  }

  const onShieldWheel = (ev: WheelEvent) => {
    if (detached) return
    // 编辑模式下站点自身交互会被屏蔽，wheel 默认无法进入 iframe 内容进行滚动。
    // 这里由父页面捕获滚轮，并把滚动动作转发给 iframe。
    ev.preventDefault()
    ev.stopPropagation()

    const win = iframeEl.contentWindow
    if (!win) return

    const deltaY = Number(ev.deltaY || 0)
    if (!deltaY) return

    try {
      win.scrollBy({ top: deltaY, left: 0, behavior: 'auto' })
    } catch {
      // 同源情况下可以访问 scrollingElement；失败则忽略
      try {
        const doc = win.document
        const se = doc.scrollingElement
        if (se) se.scrollTop += deltaY
      } catch {
        // ignore
      }
    }
  }
  if (parentShield) {
    ;(parentShield as any).addEventListener('pointermove', onShieldPointerMove as any, true)
    ;(parentShield as any).addEventListener('pointerleave', onShieldPointerLeave as any, true)
    ;(parentShield as any).addEventListener('click', onShieldClick as any, true)
    ;(parentShield as any).addEventListener('dblclick', onShieldDblClick as any, true)
    parentShieldWheelListener = onShieldWheel
    ;(parentShield as any).addEventListener('wheel', onShieldWheel as any, {
      capture: true,
      passive: false,
    })
  }
  parentWindowPointerListener = () => {
    if (!parentShield) return
    ;(parentShield as any).removeEventListener('pointermove', onShieldPointerMove as any, true)
    ;(parentShield as any).removeEventListener('pointerleave', onShieldPointerLeave as any, true)
    ;(parentShield as any).removeEventListener('click', onShieldClick as any, true)
    ;(parentShield as any).removeEventListener('dblclick', onShieldDblClick as any, true)
    if (parentShieldWheelListener) {
      try {
        parentShield?.removeEventListener('wheel', parentShieldWheelListener as any, true)
      } catch {
        // ignore
      }
      parentShieldWheelListener = null
    }
  }

  // 同域名：父页面可直接访问 iframe 的 window / document
  const win = iframeEl.contentWindow
  if (!win) {
    window.removeEventListener('message', handleMessage)
    throw new Error('iframe 尚未就绪')
  }
  const safeWin: Window = win

  function getIframeDocHref(): string | null {
    try {
      return (
        iframeEl.contentWindow?.location?.href ?? iframeEl.contentDocument?.location?.href ?? null
      )
    } catch {
      return null
    }
  }

  /** 监听 iframe 内 SPA 路由变化（hash / history），清除选中框与父页状态 */
  let lastIframeHrefForNav: string | null = null
  let navPollTimer: number | null = null
  let iframeNavListenersCleanup: (() => void) | null = null

  function clearSelectionEverywhere() {
    try {
      injected?.clearSelection?.()
    } catch {
      // ignore
    }
    lastHoverRect = null
    lastSelectedRect = null
    syncParentBoxes()
    try {
      opts.onNavigateClear?.()
    } catch {
      // ignore
    }
  }

  function bindIframeNavigationListeners() {
    iframeNavListenersCleanup?.()
    iframeNavListenersCleanup = null
    try {
      const w = iframeEl.contentWindow
      if (!w) return
      const onNav = () => {
        if (detached) return
        const h = getIframeDocHref()
        if (!h || h === 'about:blank') return
        if (lastIframeHrefForNav !== null && h !== lastIframeHrefForNav) {
          lastIframeHrefForNav = h
          clearSelectionEverywhere()
        } else {
          lastIframeHrefForNav = h
        }
      }
      w.addEventListener('hashchange', onNav)
      w.addEventListener('popstate', onNav)
      iframeNavListenersCleanup = () => {
        try {
          w.removeEventListener('hashchange', onNav)
          w.removeEventListener('popstate', onNav)
        } catch {
          // ignore
        }
      }
    } catch {
      // ignore
    }
  }

  function stopNavWatch() {
    if (navPollTimer != null) {
      window.clearInterval(navPollTimer)
      navPollTimer = null
    }
    try {
      iframeNavListenersCleanup?.()
    } catch {
      // ignore
    }
    iframeNavListenersCleanup = null
    lastIframeHrefForNav = null
  }

  function onInjectionReady() {
    stopNavWatch()
    lastIframeHrefForNav = getIframeDocHref() ?? null
    navPollTimer = window.setInterval(() => {
      if (detached) return
      const h = getIframeDocHref()
      if (!h || h === 'about:blank') return
      if (lastIframeHrefForNav === null) {
        lastIframeHrefForNav = h
        return
      }
      if (h !== lastIframeHrefForNav) {
        lastIframeHrefForNav = h
        clearSelectionEverywhere()
      }
    }, 250)
    bindIframeNavigationListeners()
  }

  function isRealIframeDocumentReady(): boolean {
    try {
      const href = getIframeDocHref()
      if (!href) return false
      // 避免在 about:blank 临时文档上注入（src 导航完成后会丢失所有注入）
      if (href === 'about:blank') return false
      const rs = iframeEl.contentDocument?.readyState
      return rs === 'interactive' || rs === 'complete'
    } catch {
      return false
    }
  }

  function doAttach() {
    injected = ensureInjectedApi(safeWin)
    injected.attach({
      hoverColor: 'rgba(14, 165, 233, 0.85)',
      selectedColor: 'rgba(37, 99, 235, 0.98)',
      selectedWidth: 3,
      hoverWidth: 2,
      messageType,
      hoverMessageType,
      parentOrigin: window.location.origin,
    })
  }

  function clearRetryTimer() {
    if (retryTimer != null) {
      try {
        window.clearInterval(retryTimer)
      } catch {
        // ignore
      }
      retryTimer = null
    }
  }

  function isInjectedOk(): boolean {
    try {
      // 核心校验：样式节点是否已写入 iframe document
      return !!iframeEl.contentDocument?.getElementById(STYLE_ID)
    } catch {
      return false
    }
  }

  function reportError(err: unknown) {
    const e = err instanceof Error ? err : new Error(String(err ?? 'unknown error'))
    try {
      opts.onError?.(e)
    } catch {
      // ignore
    }
  }

  function attemptAttachWithRetry() {
    clearRetryTimer()
    let attempts = 0
    const maxAttempts = 200 // ~20s（预览构建/首屏资源加载可能较慢）
    const intervalMs = 100

    const runOnce = () => {
      if (detached) {
        clearRetryTimer()
        return
      }
      if (isInjectedOk()) {
        try {
          iframeEl.dataset.glyahhVisualEditorInjected = 'true'
        } catch {
          // ignore
        }
        try {
          onInjectionReady()
        } catch {
          // ignore
        }
        clearRetryTimer()
        return
      }
      // 必须等真实页面（非 about:blank）ready 后再注入，否则会被后续导航覆盖
      if (!isRealIframeDocumentReady()) {
        attempts += 1
        if (attempts >= maxAttempts) {
          clearRetryTimer()
          reportError(
            new Error(
              '可视化编辑注入失败：iframe 页面未就绪或仍处于 about:blank（可能预览仍在跳转/加载）',
            ),
          )
        }
        return
      }
      attempts += 1
      try {
        doAttach()
      } catch {
        // ignore
      }
      if (isInjectedOk()) {
        try {
          iframeEl.dataset.glyahhVisualEditorInjected = 'true'
        } catch {
          // ignore
        }
        try {
          onInjectionReady()
        } catch {
          // ignore
        }
        clearRetryTimer()
        return
      }
      if (attempts >= maxAttempts) {
        clearRetryTimer()
        // 不再静默：让上层能感知到当前 iframe 无法被注入（常见原因：仍跨域 / CSP / 未就绪）
        reportError(
          new Error(
            '可视化编辑注入失败：无法向预览 iframe 写入样式/事件（可能仍存在跨域或页面未就绪）',
          ),
        )
      }
    }

    // 立即试一次，再进入轮询
    try {
      runOnce()
    } catch (e) {
      reportError(e)
      return
    }
    if (!isInjectedOk() && !detached) {
      retryTimer = window.setInterval(() => {
        try {
          runOnce()
        } catch (e) {
          clearRetryTimer()
          reportError(e)
        }
      }, intervalMs)
    }
  }

  // 关键：始终在 iframe 的真实页面 load 后注入，避免注入到 about:blank 临时文档
  const onLoad = () => {
    if (detached) return
    attemptAttachWithRetry()
  }
  iframeEl.addEventListener('load', onLoad)
  iframeLoadListener = () => iframeEl.removeEventListener('load', onLoad)

  // 若当前已经是“真实页面且 ready”，立即尝试一次（不必等下一次 load）
  if (isRealIframeDocumentReady()) {
    attemptAttachWithRetry()
  }

  return {
    detach: () => {
      if (detached) return
      detached = true
      try {
        stopNavWatch()
      } catch {
        // ignore
      }
      window.removeEventListener('message', handleMessage)
      clearRetryTimer()
      try {
        iframeLoadListener?.()
      } catch {
        // ignore
      }
      iframeLoadListener = null
      try {
        parentScrollListener?.()
      } catch {
        // ignore
      }
      parentScrollListener = null
      try {
        parentIframePointerListener?.()
      } catch {
        // ignore
      }
      parentIframePointerListener = null
      try {
        parentWindowPointerListener?.()
      } catch {
        // ignore
      }
      parentWindowPointerListener = null
      try {
        injected?.detach()
      } catch {
        // ignore
      }
      injected = null
      lastHoverRect = null
      lastSelectedRect = null
      hideParentBox(parentHoverBox)
      hideParentBox(parentSelectedBox)
      try {
        parentHoverBox?.remove()
      } catch {
        // ignore
      }
      try {
        parentSelectedBox?.remove()
      } catch {
        // ignore
      }
      parentHoverBox = null
      parentSelectedBox = null
      try {
        parentShield?.remove()
      } catch {
        // ignore
      }
      parentShield = null
    },
  }
}

/** 与 formatSelectedElementForPrompt / 历史 message 正文中的可视化编辑块标题一致 */
export const VISUAL_EDIT_BLOCK_TITLE = '[可视化编辑：已选中元素]'

export function formatSelectedElementForPrompt(info: VisualSelectedElementInfo): string {
  const lines: string[] = []
  const title = info.cssSelector || info.xpath || info.tagName
  lines.push(VISUAL_EDIT_BLOCK_TITLE)
  lines.push(`- locator: ${title}`)
  if (info.tagName) lines.push(`- tag: ${info.tagName}`)
  if (info.id) lines.push(`- id: ${info.id}`)
  if (info.className) lines.push(`- class: ${info.className}`)
  if (info.name) lines.push(`- name: ${info.name}`)
  if (info.role) lines.push(`- role: ${info.role}`)
  if (info.type) lines.push(`- type: ${info.type}`)
  if (info.ariaLabel) lines.push(`- aria-label: ${info.ariaLabel}`)
  if (info.placeholder) lines.push(`- placeholder: ${info.placeholder}`)
  if (info.href) lines.push(`- href: ${info.href}`)
  if (info.src) lines.push(`- src: ${info.src}`)
  if (info.text) lines.push(`- text: ${info.text}`)
  if (info.cssSelector) lines.push(`- cssSelector: ${info.cssSelector}`)
  if (info.xpath) lines.push(`- xpath: ${info.xpath}`)
  return lines.join('\n')
}
