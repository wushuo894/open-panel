const baseUrl = new URL(import.meta.env.BASE_URL, document.baseURI)
const absoluteUrl = /^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i

export function appUrl(path = '') {
  const value = String(path)
  if (!value || absoluteUrl.test(value)) return value
  return new URL(value.replace(/^\/+/, ''), baseUrl).toString()
}
