/**
 * vitest 全局准备。
 *
 * jsdom 没有实现 window.matchMedia，而组件在模块初始化阶段就会读它（判断是否窄屏分栏），
 * 缺了这一项会让整个组件挂载失败。这里补一个最小实现，只提供项目用得到的几个成员。
 */
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  })
}

export {}
