export const DEFAULT_THEME_COLOR = '#d8f257'

export function normalizeThemeColor(value, fallback = DEFAULT_THEME_COLOR) {
  const text = String(value || '').trim()
  if (/^#[0-9a-f]{6}$/i.test(text)) return text.toLowerCase()
  if (/^#[0-9a-f]{3}$/i.test(text)) {
    return `#${text.slice(1).split('').map(char => char + char).join('')}`.toLowerCase()
  }
  return fallback
}

export function applySiteTheme(theme, site) {
  const configured = site?.theme || 'system'
  const dark = configured === 'dark' || (configured === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  const base = normalizeThemeColor(site?.themeColor)
  const lightPrimary = tone(base, { minSaturation: 0.38, minLightness: 0.26, maxLightness: 0.4 })
  const darkPrimary = tone(base, { minSaturation: 0.42, minLightness: 0.66, maxLightness: 0.78 })
  const lightSecondary = tone(base, { minSaturation: 0.42, minLightness: 0.46, maxLightness: 0.64 })
  const darkSecondary = tone(base, { minSaturation: 0.36, minLightness: 0.4, maxLightness: 0.56 })

  theme.themes.value.openPanelLight.colors.primary = lightPrimary
  theme.themes.value.openPanelLight.colors.secondary = lightSecondary
  theme.themes.value.openPanelLight.colors.accent = lightSecondary
  theme.themes.value.openPanelDark.colors.primary = darkPrimary
  theme.themes.value.openPanelDark.colors.secondary = darkSecondary
  theme.themes.value.openPanelDark.colors.accent = darkPrimary
  theme.global.name.value = dark ? 'openPanelDark' : 'openPanelLight'
}

export function extractThemeColor(imageUrl) {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.crossOrigin = 'anonymous'
    image.decoding = 'async'
    image.onload = () => {
      try {
        resolve(sampleImageColor(image))
      } catch (error) {
        reject(error)
      }
    }
    image.onerror = () => reject(new Error('壁纸加载失败，远程图片可能不允许跨域读取'))
    image.src = imageUrl
  })
}

function sampleImageColor(image) {
  const canvas = document.createElement('canvas')
  canvas.width = 72
  canvas.height = 72
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) throw new Error('当前浏览器不支持壁纸取色')
  context.drawImage(image, 0, 0, canvas.width, canvas.height)
  const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
  const buckets = new Map()

  for (let index = 0; index < pixels.length; index += 8) {
    if (pixels[index + 3] < 160) continue
    const red = pixels[index]
    const green = pixels[index + 1]
    const blue = pixels[index + 2]
    const { saturation, lightness } = rgbToHsl(red, green, blue)
    if (saturation < 0.18 || lightness < 0.12 || lightness > 0.88) continue
    const key = `${red >> 4}-${green >> 4}-${blue >> 4}`
    const weight = (0.45 + saturation) * (1 - Math.abs(lightness - 0.52) * 1.25)
    const bucket = buckets.get(key) || { red: 0, green: 0, blue: 0, count: 0, score: 0 }
    bucket.red += red
    bucket.green += green
    bucket.blue += blue
    bucket.count++
    bucket.score += Math.max(0.1, weight)
    buckets.set(key, bucket)
  }

  const selected = [...buckets.values()].sort((left, right) => right.score - left.score)[0]
  if (!selected) throw new Error('壁纸中没有可用的主题色')
  const color = rgbToHex(selected.red / selected.count, selected.green / selected.count, selected.blue / selected.count)
  return tone(color, { minSaturation: 0.44, minLightness: 0.42, maxLightness: 0.62 })
}

function tone(hex, { minSaturation, minLightness, maxLightness }) {
  const [red, green, blue] = hexToRgb(hex)
  const hsl = rgbToHsl(red, green, blue)
  hsl.saturation = Math.max(minSaturation, hsl.saturation)
  hsl.lightness = Math.min(maxLightness, Math.max(minLightness, hsl.lightness))
  return rgbToHex(...hslToRgb(hsl.hue, hsl.saturation, hsl.lightness))
}

function hexToRgb(hex) {
  const value = Number.parseInt(normalizeThemeColor(hex).slice(1), 16)
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255]
}

function rgbToHex(red, green, blue) {
  const hex = value => Math.round(value).toString(16).padStart(2, '0')
  return `#${hex(red)}${hex(green)}${hex(blue)}`
}

function rgbToHsl(red, green, blue) {
  const r = red / 255
  const g = green / 255
  const b = blue / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const delta = max - min
  let hue = 0
  if (delta) {
    if (max === r) hue = ((g - b) / delta) % 6
    else if (max === g) hue = (b - r) / delta + 2
    else hue = (r - g) / delta + 4
    hue = ((hue * 60) + 360) % 360
  }
  const lightness = (max + min) / 2
  const saturation = delta ? delta / (1 - Math.abs(2 * lightness - 1)) : 0
  return { hue, saturation, lightness }
}

function hslToRgb(hue, saturation, lightness) {
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation
  const segment = hue / 60
  const x = chroma * (1 - Math.abs(segment % 2 - 1))
  let red = 0
  let green = 0
  let blue = 0
  if (segment < 1) [red, green] = [chroma, x]
  else if (segment < 2) [red, green] = [x, chroma]
  else if (segment < 3) [green, blue] = [chroma, x]
  else if (segment < 4) [green, blue] = [x, chroma]
  else if (segment < 5) [red, blue] = [x, chroma]
  else [red, blue] = [chroma, x]
  const match = lightness - chroma / 2
  return [(red + match) * 255, (green + match) * 255, (blue + match) * 255]
}
