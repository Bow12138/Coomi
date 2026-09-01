/// <reference types="vite/client" />

interface Window {
  __coomiHandleSystemBack?: () => boolean
  __coomiApplyAppearance?: (config: AppearanceConfig) => void
  CoomiAndroid?: {
    openDashboard(): void
    importFiles?(): void
    importFilesForRequest?(requestId: string): void
    authorizeFolder?(): void
    exportFile?(path: string, suggestedName: string): void
    exportFileForRequest?(requestId: string, path: string, suggestedName: string): void
    openFile?(path: string): void
    /** 保存图片（data URL）到相册或下载目录。 */
    saveImageData?(dataUrl: string, fileName: string): void
    /** 通知原生层任务运行状态（更新通知栏：执行中 / 已完成）。 */
    updateTaskStatus?(status: string): void
    /** 1.4.5 后台任务通知协议，携带会话定位信息。 */
    updateTaskStatusDetails?(status: string, sessionId: string, background: boolean): void
    /** 获取设备与 App 诊断信息（报错反馈使用，不含对话内容）。 */
    getDiagnostics?(): string
    /** 原生上报报错反馈（绕过 WebView CORS）：json 为反馈体，callbackId 用于异步回调。 */
    sendFeedback?(json: string, callbackId: string): void
    getThemeMode?(): string
    setThemeMode?(mode: string): void
    getDigitalLifeEnabled?(): boolean
    setDigitalLifeEnabled?(enabled: boolean): void
    getAppearanceConfig?(): string
    /** 当前安装的 versionCode（检查更新页对比用）。 */
    getAppVersionCode?(): number
    /** 下载并安装更新 APK（url 为 APK 直链，version 用于文件名/提示）。 */
    installApk?(url: string, version: string): void
    /** 是否已授予录音权限。 */
    hasMicPermission?(): boolean
    /** 请求录音权限。 */
    requestMicPermission?(): void
    /** 开始语音识别（本地 sherpa-ncnn，结果经 window.__coomiVoiceResult 回调）。 */
    startVoiceInput?(): void
    /** 停止当前语音识别。 */
    stopVoiceInput?(): void
    /** 朗读文本（系统 TTS，SIMPLE_TTS，按 100 字分段）。 */
    speak?(text: string): void
    /** 停止朗读。 */
    stopSpeaking?(): void
    /** Shizuku 是否可用（已连接并授权）。 */
    shizukuAvailable?(): boolean
    /** 请求 Shizuku 授权。 */
    requestShizukuPermission?(): void
    /** 在白名单内执行 adb 命令（input/screencap/am/settings get）。 */
    execAdb?(command: string): string
    /** 截图到本地缓存并返回文件路径。 */
    screenCapture?(): string
    /** 无障碍服务是否已连接。 */
    accessibilityEnabled?(): boolean
    /** 通过无障碍执行操作（tap/swipe/input_text/back/home/long_press）。 */
    accessibilityAction?(action: string, x: number, y: number, x2: number, y2: number, text: string): string
    /** 通过无障碍导出当前控件树（JSON 字符串）。 */
    accessibilityDump?(): string
    /** 打开系统「无障碍」设置页。 */
    openAccessibilitySettings?(): void
    /** Shizuku 授权状态诊断：service_down / not_permitted / granted。 */
    shizukuStatus?(): string
  }
}

interface AppearanceConfig {
  customEnabled?: boolean
  colors?: Record<string, string>
  chatBackground?: boolean
  chatMask?: number
  revision?: number
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'vue-virtual-scroller' {
  import type { DefineComponent } from 'vue'

  export const DynamicScroller: DefineComponent
  export const DynamicScrollerItem: DefineComponent
}
