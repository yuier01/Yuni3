<script setup>
import { ref, computed, onMounted } from 'vue'
import { getPluginList, enablePlugin, disablePlugin } from '../../api/plugin'
import PluginDetailModal from '../../components/PluginDetailModal.vue'

const modules = ref([])
const loading = ref(false)
const error = ref('')

const expandedModules = ref({})
const searchQuery = ref('')

/** 正在切换全局开关的插件 ID 集合，用于禁用按钮避免重复点击。 */
const togglingIds = ref(new Set())

/** 操作结果提示（成功/失败），3 秒后自动消失。 */
const toast = ref(null)
let toastTimer = null

function showToast(text, ok = true) {
  toast.value = { text, ok }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = null }, 3000)
}

const filteredModules = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return modules.value
  return modules.value.filter(mod => {
    if (mod.module_name?.toLowerCase().includes(q)) return true
    if (mod.module_id?.toLowerCase().includes(q)) return true
    return mod.plugins.some(p => p.name?.toLowerCase().includes(q))
  })
})

const totalPlugins = computed(() =>
  filteredModules.value.reduce((sum, m) => sum + m.plugins.length, 0)
)

onMounted(fetchList)

async function fetchList() {
  loading.value = true
  error.value = ''
  try {
    modules.value = await getPluginList()
  } catch (e) {
    error.value = e.message || '加载失败'
    modules.value = []
  } finally {
    loading.value = false
  }
}

function toggleExpand(moduleId) {
  expandedModules.value[moduleId] = !expandedModules.value[moduleId]
}

/**
 * 切换插件的全局开关。
 *
 * 传 groupId = null 表示全局操作——后端会走 setGlobalEnabled（改默认值本身），
 * 而不是 setEnabled（改某个群与默认值的差异）。两者不可互相替代：
 * 「整体打开一个默认关闭的插件」只能靠全局开关，逐个群开启等于枚举无限集。
 *
 * 内置插件只有 bot 拥有者能启停，后端会拒绝并返回原因；这里把原因原样展示，
 * 而不是笼统地显示「操作失败」。
 */
async function toggleGlobal(plugin) {
  if (togglingIds.value.has(plugin.full_id)) return
  const next = !plugin.global_enabled
  togglingIds.value = new Set(togglingIds.value).add(plugin.full_id)
  try {
    if (next) {
      await enablePlugin(plugin.full_id, null)
    } else {
      await disablePlugin(plugin.full_id, null)
    }
    plugin.global_enabled = next
    showToast(next ? `已全局启用「${plugin.name}」` : `已全局禁用「${plugin.name}」`)
  } catch (e) {
    showToast(e.message || '操作失败', false)
  } finally {
    const s = new Set(togglingIds.value)
    s.delete(plugin.full_id)
    togglingIds.value = s
  }
}

/*
 * 标签与配色的键是「运行状态」而不是旧的插件类型。
 *
 * P6 之前这里映射的是 SCHEDULED / IMMEDIATE / COMMAND / PATTERN / ... 八种
 * 插件类型。新系统取消了插件类型这一声明——「这个插件是什么类型」不再是
 * 元数据里的一个字段，而是「它在 apply 里注册了什么能力」的推论。
 * 因此后端改为返回运行状态，前端需要同步。
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

/**
 * 状态标签。
 *
 * 未知值时原样显示（而不是空白），这样后端新增状态时前端不会变成一片空。
 */
function stateLabel(state) {
  return typeLabel[state] || state || '未知'
}

function stateColor(state) {
  return typeColor[state] || 'bg-gray-50 text-gray-500'
}

const PREVIEW_LIMIT = 2

const detailPlugin = ref(null)
const detailVisible = ref(false)

function openDetail(plugin) {
  detailPlugin.value = plugin
  detailVisible.value = true
}

function closeDetail() {
  detailVisible.value = false
  setTimeout(() => { detailPlugin.value = null }, 200)
}
</script>

<template>
  <div class="flex flex-col min-h-0 h-full">
    <!-- 搜索 + 统计 -->
    <div class="shrink-0 mb-5">
      <div class="relative w-56">
        <span class="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-pink-300">🔍</span>
        <input
          v-model="searchQuery"
          type="text"
          placeholder="搜索模块名或插件名..."
          class="pl-9 pr-4 py-1.5 text-sm 2xl:text-base rounded-xl border border-pink-200
                 bg-white/80 text-pink-700 placeholder-pink-300 w-full
                 focus:outline-none focus:border-pink-400 focus:bg-white transition-colors"
        />
      </div>
      <div
        v-if="!loading && !error && totalPlugins"
        class="flex items-center gap-4 mt-3 text-xs 2xl:text-sm text-pink-400"
      >
        <span>📦 {{ filteredModules.length }} 个模块</span>
        <span>🧩 {{ totalPlugins }} 个插件</span>
      </div>
    </div>

    <div class="flex-1 overflow-y-auto min-h-0 -mx-3 px-3">
    <!-- 加载态 -->
    <div v-if="loading" class="yuni-card">
      <div class="flex items-center gap-3 text-pink-400 py-4">
        <span class="animate-pulse text-lg">🌸</span>
        <span>正在加载插件列表...</span>
      </div>
    </div>

    <!-- 错误态 -->
    <div v-else-if="error" class="yuni-card">
      <div class="flex items-center justify-between py-2">
        <span class="text-pink-500">⚠ {{ error }}</span>
        <button
          class="text-sm px-4 py-1.5 rounded-lg bg-pink-100 text-pink-600
                 font-medium hover:bg-pink-200 transition-colors"
          @click="fetchList"
        >重试</button>
      </div>
    </div>

    <!-- 空态：无插件 -->
    <div v-else-if="!modules.length" class="yuni-card">
      <div class="text-center py-12">
        <div class="text-4xl mb-3">📦</div>
        <p class="text-pink-400 2xl:text-lg">暂未加载任何插件</p>
        <p class="text-sm 2xl:text-base text-pink-300 mt-1">
          请确认插件 JAR 包已放入 plugins 目录并重启服务
        </p>
      </div>
    </div>

    <!-- 空态：搜索无结果 -->
    <div v-else-if="!filteredModules.length" class="yuni-card">
      <div class="text-center py-12">
        <div class="text-4xl mb-3">🔍</div>
        <p class="text-pink-400 2xl:text-lg">未找到匹配的插件或模块</p>
        <p class="text-sm 2xl:text-base text-pink-300 mt-1">
          试试其他关键词？
        </p>
      </div>
    </div>

    <!-- 模块卡片网格 -->
    <div v-else class="grid gap-5 grid-cols-1 lg:grid-cols-3">
      <div
        v-for="mod in filteredModules"
        :key="mod.module_id"
        class="yuni-card !p-0 overflow-hidden flex flex-col"
      >
        <!-- 模块头部 -->
        <div class="flex items-center gap-2 px-5 py-3 bg-pink-50/80 border-b border-pink-100">
          <span class="text-base">📦</span>
          <h3 class="font-bold text-base 2xl:text-lg text-pink-700 flex-1">
            {{ mod.module_name || mod.module_id }}
          </h3>
          <span class="text-xs text-pink-400">{{ mod.plugins.length }} 插件</span>
        </div>

        <!-- 插件列表 -->
        <div class="flex-1 divide-y divide-pink-50 px-5">
          <div
            v-for="p in mod.plugins"
            :key="p.full_id"
            v-show="expandedModules[mod.module_id] || mod.plugins.indexOf(p) < PREVIEW_LIMIT"
            class="py-3"
          >
            <div class="flex items-center gap-2 mb-1">
              <span class="font-bold text-sm 2xl:text-base text-pink-800 flex-1">{{ p.name }}</span>
              <span
                class="shrink-0 text-xs px-1.5 py-0.5 rounded-full font-medium"
                :class="p.built_in ? 'bg-pink-100 text-pink-600' : 'bg-gray-100 text-gray-500'"
              >{{ p.built_in ? '内置' : '外部' }}</span>
              <span
                class="shrink-0 text-xs px-1.5 py-0.5 rounded-full font-medium"
                :class="stateColor(p.type)"
              >{{ stateLabel(p.type) }}</span>
              <!--
                全局开关。P6 之前这个入口完全缺失（后端也没有对应能力），
                于是「插件装了但没生效」在管理端只能靠猜。
              -->
              <button
                class="shrink-0 text-xs px-2 py-0.5 rounded-full font-medium border transition-colors
                       disabled:opacity-40 disabled:cursor-not-allowed"
                :class="p.global_enabled
                  ? 'bg-emerald-50 text-emerald-600 border-emerald-200 hover:bg-emerald-100'
                  : 'bg-gray-50 text-gray-500 border-gray-200 hover:bg-gray-100'"
                :disabled="togglingIds.has(p.full_id)"
                :title="p.global_enabled ? '全局启用中，点击禁用' : '全局已禁用，点击启用'"
                @click="toggleGlobal(p)"
              >{{ p.global_enabled ? '全局开' : '全局关' }}</button>
              <button
                class="shrink-0 text-xs px-2.5 py-1 rounded-lg bg-pink-100 text-pink-600
                       font-medium hover:bg-pink-200 hover:text-pink-700
                       active:scale-95 transition-all border border-pink-200"
                @click="openDetail(p)"
              >详情 →</button>
            </div>
            <p class="text-xs 2xl:text-sm text-pink-400 leading-relaxed line-clamp-2">
              {{ p.description }}
            </p>
            <div class="flex items-center gap-3 mt-1 text-xs 2xl:text-sm text-pink-300">
              <span>v{{ p.version }}</span>
              <span>{{ p.author }}</span>
            </div>
          </div>
        </div>

        <button
          v-if="mod.plugins.length > PREVIEW_LIMIT"
          class="px-5 py-2.5 text-xs 2xl:text-sm text-pink-500 hover:text-pink-700
                 hover:bg-pink-50 transition-colors font-medium border-t border-pink-50"
          @click="toggleExpand(mod.module_id)"
        >
          {{ expandedModules[mod.module_id] ? '收起' : `展开全部 ${mod.plugins.length} 个插件` }}
        </button>
      </div>
    </div>
    </div>
    <!-- /滚动容器 -->

    <PluginDetailModal
      :plugin="detailPlugin"
      :visible="detailVisible"
      @close="closeDetail"
    />

    <!-- 操作结果提示 -->
    <Transition name="toast">
      <div
        v-if="toast"
        class="fixed bottom-6 left-1/2 -translate-x-1/2 z-[60] px-4 py-2.5 rounded-xl
               text-sm font-medium shadow-lg"
        :class="toast.ok
          ? 'bg-emerald-500 text-white'
          : 'bg-rose-500 text-white'"
      >{{ toast.text }}</div>
    </Transition>
  </div>
</template>

<style scoped>
.toast-enter-active { transition: opacity 0.2s ease-out, transform 0.2s ease-out; }
.toast-leave-active { transition: opacity 0.2s ease-in, transform 0.2s ease-in; }
.toast-enter-from,
.toast-leave-to { opacity: 0; transform: translate(-50%, 8px); }
</style>
