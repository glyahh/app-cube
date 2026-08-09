export function scrollElementToEnd(element: HTMLElement | null | undefined): void {
  if (!element) return
  element.scrollTop = element.scrollHeight
}
