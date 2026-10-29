export function slug(text) {
  return text.trim().toLowerCase().replace(/\s+/g, '-');
}
