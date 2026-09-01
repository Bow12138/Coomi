/**
 * 品牌检测：dev 版（默认端口 18765）显示 Anna，官方版显示 Coomi。
 * dev 与官方共用同一份 web.zip，靠运行时端口区分。
 * 端口判断依据 CoomiConstants.PORT_CANDIDATES：
 *   dev 首选 18765，官方首选 8765。18765 为 dev 唯一独占首选端口。
 */
export function isDevBrand(): boolean {
  const port = window.location.port
  return port === '18765' || window.location.href.includes(':18765')
}

export function appName(): string {
  return isDevBrand() ? 'Anna' : 'Coomi'
}

export function appDisplayName(): string {
  return isDevBrand() ? 'Anna（安娜）' : 'Coomi'
}
