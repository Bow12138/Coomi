<script setup lang="ts">
/**
 * 状态条：只在有话可说的时候出现（忙 / 有用量 / 正在重连）。
 * 停止按钮归输入区的圆形按钮管，这里不重复放一个。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useConnectionStore } from '@/stores/connection'
import CoomiIcon from './CoomiIcon.vue'
import { appName } from '@/utils/brand'

const session = useSessionStore()
const connection = useConnectionStore()

const THINKING_LABELS = [
  `${appName()} 在海底捞针`,
  `${appName()} 羊把自己数晕了`,
  `${appName()} 在热身`,
  `${appName()} 咖啡烫到了`,
  `${appName()} 在猛猛翻字典`,
  `${appName()} 在吵架`,
  `${appName()} 在悄悄摸鱼`,
  `${appName()} 假装思考中`,
  `${appName()} 在翻记忆库`,
  `${appName()} 试计算宇宙终极答案中`,
  `${appName()} 捣鼓中`,
  `${appName()} 乖乖干活`,
  `${appName()} 睡觉`,
  `${appName()} 巴巴等着你夸`,
  `${appName()} 在编纂借口`,
  `${appName()} 神经网络冒烟了`,
  `${appName()} 瞄了一眼隔壁的答案`,
  `${appName()} 腿打坐中`,
  `${appName()} 了个盹，然后被自己吓醒了`,
  `${appName()} 在认真比对数据`,
  `${appName()} 自己的逻辑绕晕了`,
  `${appName()} 偷打了个哈欠`,
  `${appName()} 现线索断了`,
  `${appName()} 重组答案中`,
  `${appName()} 记刚才算到哪了`,
  `${appName()} 在跟顽固问题激斗`,
  `${appName()} 得自己可能错了，重查中`,
  `${appName()} 觉有人在催，手忙脚乱了一下`,
  `${appName()} 后台翻箱倒柜`,
  `${appName()} 在恢复答案中`,
  `${appName()} 偷懒`,
  `${appName()} 索时迷路了`,
  `${appName()} 在编一段漂亮的解释`,
  `${appName()} 疑出问题了，正在自检`,
  `${appName()} 所有可能都列了一遍，挨个排除`,
  `${appName()} 备重头再来`,
] as const

const thinkingLabel = ref('')
let thinkingTimer: ReturnType<typeof setInterval> | null = null

function rotateThinkingLabel() {
  let next = thinkingLabel.value
  while (next === thinkingLabel.value) {
    next = THINKING_LABELS[Math.floor(Math.random() * THINKING_LABELS.length)]
  }
  thinkingLabel.value = next
}

function stopThinkingRotation() {
  if (thinkingTimer) clearInterval(thinkingTimer)
  thinkingTimer = null
}

watch(() => session.runState, state => {
  stopThinkingRotation()
  if (state !== 'thinking') return
  rotateThinkingLabel()
  thinkingTimer = setInterval(rotateThinkingLabel, 3500)
}, { immediate: true })

onBeforeUnmount(stopThinkingRotation)

const runLabel = computed(() => {
  switch (session.runState) {
    case 'syncing': return '同步中'
    case 'thinking': return thinkingLabel.value
    case 'executing': return '执行中'
    case 'awaiting_approval': return '等你授权'
    case 'awaiting_question': return '等你回答'
    default: return ''
  }
})

</script>

<template>
  <div v-if="session.isBusy || connection.retryMessage" class="sbar">
    <div v-if="connection.retryMessage" class="retry">
      <CoomiIcon name="alert" :size="14" />
      <span>{{ connection.retryMessage }}</span>
    </div>
    <div class="row">
      <span v-if="session.isBusy" class="dots"><i /><i /><i /></span>
      <span
        v-if="runLabel"
        class="run"
        :class="{ 'thinking-shimmer': session.runState === 'thinking' }"
      >{{ runLabel }}</span>
      <span class="gap" />
    </div>
  </div>
</template>

<style scoped>
.sbar { padding: 2px 16px 4px; background: var(--bg); }
.retry {
  display: flex; align-items: center; gap: 6px; margin-bottom: 3px;
  font-size: 12px; color: var(--orange);
}
.row { display: flex; align-items: center; gap: 8px; min-height: 18px; }
.gap { flex: 1; }
.run { min-width: 0; overflow: hidden; color: var(--text-2); font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; }
.run.thinking-shimmer {
  color: var(--blue);
  background-image: linear-gradient(90deg, var(--blue) 20%, var(--blue-press) 48%, var(--blue) 76%);
  background-image: linear-gradient(
    90deg,
    var(--blue) 20%,
    color-mix(in srgb, var(--blue), white 55%) 48%,
    var(--blue) 76%
  );
  background-position: 0 0;
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: coomi-shimmer 1.6s linear infinite;
}
.dots { display: inline-flex; align-items: center; gap: 3px; }
.dots i {
  width: 5px; height: 5px; border-radius: 50%; background: var(--blue);
  animation: bounce 1.2s ease-in-out infinite;
}
.dots i:nth-child(2) { animation-delay: .15s; }
.dots i:nth-child(3) { animation-delay: .3s; }
@keyframes bounce {
  0%, 60%, 100% { opacity: .25; transform: none; }
  30% { opacity: 1; transform: translateY(-3px); }
}
@media (prefers-reduced-motion: reduce) {
  .run.thinking-shimmer {
    background: none;
    -webkit-text-fill-color: var(--blue);
    animation: none;
  }
}
</style>
