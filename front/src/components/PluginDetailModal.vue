<script setup>
import { watch } from 'vue'

const props = defineProps({
  plugin: { type: Object, default: null },
  visible: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

/*
 * 标签与配色的键是「运行状态」而不是旧的插件类型。
 *
 * P6 之前这里映射的是 SCHEDULED / IMMEDIATE / COMMAND / PATTERN / ... 八种
 * 插件类型。新系统取消了插件类型这一声明——它不再是元数据里的字段，
 * 而是「插件在 apply 里注册了什么能力」的推论。后端因此改为返回运行状态。
 */
const typeLabel = {
  ACTIVE: '运行中', FAILED: '加载失败', PENDING: '等待依赖',
  LOADING: '加载中', UNLOADING: '卸载中', DISPOSED: '已卸载',
}

const typeColor = {
  ACTIVE: 'bg-emerald-50 text-emerald-600',
  FAILED: 'bg-rose-50 text-rose-600',
  PENDING: 'bg-amber-50 text-amber-600',
  LOADING: 'bg-blue-50 text-blue-600',
  UNLOADING: 'bg-gray-50 text-gray-500',
  DISPOSED: 'bg-gray-100 text-gray-400',
}

/** 状态标签；未知值原样显示，后端新增状态时前端不会变成空白。 */
function stateLabel(state) {
  return typeLabel[state] || state || '未知'
}

function stateColor(state) {
  return typeColor[state] || 'bg-gray-50 text-gray-500'
}

function onKeydown(e) {
  if (e.key === 'Escape') emit('close')
}
watch(() => props.visible, (v) => {
  if (v) document.addEventListener('keydown', onKeydown)
  else document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="plugin"
      class="fixed inset-0 z-50 flex items-center justify-center p-6"
      :class="visible ? 'pointer-events-auto' : 'pointer-events-none'"
    >
      <!-- 遮罩，点击关闭 -->
      <Transition name="overlay">
        <div v-if="visible" class="absolute inset-0 bg-black/40" @click="emit('close')"></div>
      </Transition>

      <!-- 弹窗卡片 -->
      <Transition name="card">
        <div
          v-if="visible"
          class="relative w-full max-w-lg max-h-[85vh] overflow-y-auto rounded-2xl shadow-xl"
          style="background: rgba(255,255,255,0.95); border: 1px solid rgba(248,165,182,0.3)"
          @click.stop
        >
            <!-- 关闭按钮 -->
            <button
              class="absolute top-4 right-4 w-8 h-8 flex items-center justify-center
                     rounded-full text-pink-300 hover:text-pink-500 hover:bg-pink-50
                     transition-colors text-lg 2xl:text-xl leading-none"
              @click="emit('close')"
            >✕</button>

            <!-- 头部 -->
            <div class="px-6 pt-6 pb-4 border-b border-pink-100">
              <h3 class="font-bold text-lg 2xl:text-xl text-pink-800 mb-2">{{ plugin.name }}</h3>
              <div class="flex items-center gap-1.5">
                <span
                  class="text-xs px-2 py-0.5 rounded-full font-medium"
                  :class="plugin.built_in
                    ? 'bg-pink-100 text-pink-600'
                    : 'bg-gray-100 text-gray-500'"
                >
                  {{ plugin.built_in ? '内置' : '外部' }}
                </span>
                <span
                  class="text-xs px-2 py-0.5 rounded-full font-medium"
                  :class="stateColor(plugin.type)"
                >
                  {{ stateLabel(plugin.type) }}
                </span>
              </div>
            </div>

            <!-- 内容 -->
            <div class="px-6 py-4 space-y-5">
              <div>
                <p class="text-xs 2xl:text-sm font-medium text-pink-500 bg-pink-50 rounded-lg px-2 py-0.5 inline-block mb-1.5">描述</p>
                <p class="text-sm 2xl:text-base text-pink-700 leading-relaxed">
                  {{ plugin.description }}
                </p>
              </div>

              <div v-if="plugin.tips && plugin.tips.length">
                <p class="text-xs 2xl:text-sm font-medium text-pink-500 bg-pink-50 rounded-lg px-2 py-0.5 inline-block mb-1.5">使用提示</p>
                <ul class="space-y-1">
                  <li
                    v-for="(tip, i) in plugin.tips"
                    :key="i"
                    class="text-sm 2xl:text-base text-pink-600 flex items-start gap-1.5"
                  >
                    <span class="text-pink-300 mt-1 shrink-0">▸</span>
                    <span>{{ tip }}</span>
                  </li>
                </ul>
              </div>

              <div class="grid grid-cols-2 gap-3">
                <div>
                  <p class="text-xs 2xl:text-sm font-medium text-pink-500 bg-pink-50 rounded-lg px-2 py-0.5 inline-block mb-0.5">版本</p>
                  <p class="text-sm 2xl:text-base text-pink-700">v{{ plugin.version }}</p>
                </div>
                <div>
                  <p class="text-xs 2xl:text-sm font-medium text-pink-500 bg-pink-50 rounded-lg px-2 py-0.5 inline-block mb-0.5">作者</p>
                  <p class="text-sm 2xl:text-base text-pink-700">{{ plugin.author }}</p>
                </div>
                <div>
                  <p class="text-xs 2xl:text-sm font-medium text-pink-500 bg-pink-50 rounded-lg px-2 py-0.5 inline-block mb-0.5">所属模块</p>
                  <p class="text-sm 2xl:text-base text-pink-700">{{ plugin.module_id }}</p>
                </div>
                <div>
                  <p class="text-xs 2xl:text-sm font-medium text-pink-500 bg-pink-50 rounded-lg px-2 py-0.5 inline-block mb-0.5">插件ID</p>
                  <p class="text-sm 2xl:text-base text-pink-400 font-mono break-all">{{ plugin.full_id }}</p>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </div>
  </Teleport>
</template>

<style scoped>
/* 遮罩 */
.overlay-enter-active { transition: opacity 0.2s ease-out; }
.overlay-leave-active { transition: opacity 0.2s ease-in; }
.overlay-enter-from,
.overlay-leave-to { opacity: 0; }

/* 卡片 */
.card-enter-active {
  transition: opacity 0.25s ease-out,
              transform 0.3s cubic-bezier(0.34, 1.3, 0.64, 1);
}
.card-leave-active { transition: all 0.2s ease-in; }
.card-enter-from {
  opacity: 0;
  transform: scale(0.9) translateY(10px);
}
.card-leave-to {
  opacity: 0;
  transform: scale(0.95) translateY(4px);
}
</style>
