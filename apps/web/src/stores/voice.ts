import { ref } from 'vue'

/**
 * 朗读回复开关（只读回复，默认关闭）。
 * localStorage 持久化，输入栏开关与消息气泡共享同一状态。
 */
const KEY = 'coomi.readAloud'

function load(): boolean {
  try { return localStorage.getItem(KEY) === '1' } catch { return false }
}

export const readAloud = ref(load())

export function setReadAloud(v: boolean) {
  readAloud.value = v
  try { localStorage.setItem(KEY, v ? '1' : '0') } catch { /* ignore */ }
}
