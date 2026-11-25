<template>
  <div class="page experiment-assistant">
    <header class="assistant-hero">
      <div class="hero-emblem" aria-hidden="true">
        <span>EA</span>
        <i></i>
      </div>
      <div class="hero-copy">
        <span class="hero-eyebrow">CONTROLLED EXPERIMENT COPILOT</span>
        <h2>实验助手</h2>
        <p>围绕真实运行、报告和证据缺口对话；助手只读，不会创建或启动任务。</p>
      </div>
      <div class="hero-side">
        <div class="hero-signals">
          <span class="signal-pill is-live"><i></i> 证据通道在线</span>
          <span class="signal-pill"><el-icon><Lock /></el-icon> 权限内只读</span>
        </div>
        <div class="assistant-actions">
          <el-button type="primary" :icon="Plus" @click="newConversation">新对话</el-button>
          <el-button class="mobile-history" :icon="ChatDotRound" @click="historyOpen = true">历史</el-button>
          <el-button :icon="Tools" @click="openTools">工具</el-button>
          <el-button :icon="Document" @click="openNotes">笔记</el-button>
        </div>
      </div>
    </header>

    <div class="assistant-layout">
      <aside class="history-sidebar" aria-label="对话历史">
        <div class="sidebar-heading">
          <div>
            <span class="sidebar-eyebrow">SESSION ARCHIVE</span>
            <h3>对话历史</h3>
          </div>
          <span class="sidebar-count">{{ sessions.length }}</span>
        </div>
        <p class="sidebar-hint">选中历史会话，继续之前的证据链。</p>
        <div class="history-list" v-loading="historyLoading">
          <p v-if="historyError" class="muted">读取失败 <el-button link type="primary" @click="loadHistory">重试</el-button></p>
          <p v-else-if="!sessions.length && !historyLoading" class="muted">暂无历史会话</p>
          <div v-for="session in sessions" :key="session.sessionId" class="history-row"
            :class="{ active: session.sessionId === sessionId }">
            <button type="button" class="history-select" :aria-current="session.sessionId === sessionId ? 'true' : undefined"
              @click="useSession(session)">
              <span class="history-title">{{ session.title }}</span>
              <small>{{ formatDate(session.updatedAt) }}</small>
            </button>
            <el-tooltip content="删除会话">
              <el-button :icon="Delete" text aria-label="删除会话" @click="removeSession(session)" />
            </el-tooltip>
          </div>
        </div>
      </aside>

      <div class="assistant-main">
        <section v-if="taskId" class="diagnostic-context">
          <span class="context-mark"><el-icon><Connection /></el-icon></span>
          <div class="context-copy">
            <span>任务上下文</span>
            <strong>任务 {{ taskId }} · {{ headerContextLabel }}</strong>
          </div>
          <router-link class="context-link" :to="{ path: '/tasks', query: { taskId } }">
            返回任务详情 <el-icon><ArrowRight /></el-icon>
          </router-link>
        </section>

        <div class="chat-shell">
          <div class="chat-shell-bar">
            <div class="chat-identity">
              <span class="live-dot"></span>
              <div>
                <strong>{{ sessionId ? '当前证据对话' : '新的证据对话' }}</strong>
                <small>{{ taskId ? '已绑定任务、运行与尝试' : '每个问题都会重新核对授权报告' }}</small>
              </div>
            </div>
            <span class="scope-tag">只读模式</span>
          </div>

          <section ref="conversation" class="conversation" aria-label="实验助手对话" aria-live="polite"
            v-loading="loadingSession">
            <div v-if="!turns.length && !loadingSession" class="assistant-welcome">
              <div class="welcome-emblem" aria-hidden="true"><span>EA</span></div>
              <span class="welcome-kicker">START WITH A REAL RUN</span>
              <h3>从一次真实运行开始</h3>
              <p class="welcome-subtitle">对照运行、解释指标、筛选子集，或生成下一轮草案。</p>
              <div class="starter-grid">
                <button v-for="starter in starterPrompts" :key="starter.title" type="button"
                  class="starter-card" :disabled="loading || loadingSession" @click="askStarter(starter.prompt)">
                  <span class="starter-icon"><el-icon><component :is="starter.icon" /></el-icon></span>
                  <span class="starter-copy">
                    <strong>{{ starter.title }}</strong>
                    <small>{{ starter.description }}</small>
                  </span>
                  <el-icon class="starter-arrow"><ArrowRight /></el-icon>
                </button>
              </div>
              <div class="trust-strip">
                <span><el-icon><Lock /></el-icon> 只读取授权报告</span>
                <span><el-icon><CircleCheck /></el-icon> 运行与尝试绑定</span>
                <span><el-icon><Warning /></el-icon> 缺失证据会明确提示</span>
              </div>
            </div>

            <article v-for="(turn, index) in turns" :key="index" class="conversation-turn">
              <div class="user-message">
                <div class="user-bubble"><p class="user-question">{{ turn.question }}</p></div>
              </div>
              <div class="assistant-message">
                <div class="assistant-avatar" aria-hidden="true">EA</div>
                <div class="assistant-body">
                  <div class="message-meta">
                    <strong>实验助手</strong>
                    <span v-if="turn.response" class="reply-state" :class="`is-${turn.response.status || 'pending'}`">
                      {{ answerStatusText(turn.response) }}
                    </span>
                    <span v-if="turn.response?.toolResults?.length" class="tool-count">
                      {{ turn.response.toolResults.length }} 条工具证据
                    </span>
                  </div>
                  <el-alert v-if="turn.error" type="error" :closable="false" :title="turn.error" />
                  <p v-else-if="!turn.response" class="processing-text">
                    <span class="thinking-dots"><i></i><i></i><i></i></span>
                    {{ taskId && index === 0 ? '正在核对失败证据…' : '正在处理…' }}
                  </p>
                  <p v-else class="answer-text">{{ turn.response.answer }}</p>

                  <ul v-if="turn.response?.status === 'needs_context' && turn.response?.taskChoices?.length" class="task-choices">
                    <li v-for="(task, taskIndex) in turn.response.taskChoices" :key="task.taskId">
                      <button type="button" class="task-choice" :disabled="loading || loadingSession"
                        @click="chooseTask(task, turn.response.structured?.taskChoiceAction, turn)">
                        <span class="choice-index">{{ String(taskIndex + 1).padStart(2, '0') }}</span>
                        <span class="choice-copy">
                          <strong>{{ task.taskName || '任务' }}</strong>
                          <small>#{{ task.taskId }}</small>
                        </span>
                        <el-icon class="choice-arrow"><ArrowRight /></el-icon>
                      </button>
                    </li>
                  </ul>

                  <details v-if="turn.response?.toolResults?.length" class="tool-activity">
                    <summary>
                      <span class="tool-summary-icon"><el-icon><Tools /></el-icon></span>
                      查看工具证据
                      <b>{{ turn.response.toolResults.length }}</b>
                    </summary>
                    <ul>
                      <li v-for="(tool, toolIndex) in turn.response.toolResults" :key="toolIndex">
                        <strong>{{ toolLabel(tool.toolName) }}</strong>
                        <span v-if="tool.status" :class="{ 'tool-failed': tool.status === 'failed' }">
                          {{ tool.status === 'failed' ? '失败' : '完成' }}
                        </span>
                        <span v-if="tool.durationMs != null">{{ tool.durationMs }} ms</span>
                        <p v-if="tool.summary">{{ tool.summary }}</p>
                      </li>
                    </ul>
                  </details>

                  <div v-if="turn.response?.citations?.length" class="evidence-block">
                    <div class="evidence-head">
                      <span><el-icon><Document /></el-icon> 证据出处</span>
                      <small>{{ turn.response.citations.length }} 条</small>
                    </div>
                    <el-collapse class="citations">
                      <el-collapse-item v-for="citation in turn.response.citations" :key="citation.citationId"
                        :name="citation.citationId" :title="citation.title">
                        <span class="citation-source">{{ citation.sourceKey }} · L{{ citation.startLine }}–{{ citation.endLine }}</span>
                        <el-button v-if="citation.targetPath" link type="primary" @click="openCitation(citation)">打开对应报告</el-button>
                        <p class="answer-text">{{ citation.snippet }}</p>
                      </el-collapse-item>
                    </el-collapse>
                  </div>

                  <div v-if="noteSnapshot(turn.response) || draftOf(turn.response)" class="result-actions">
                    <el-button v-if="noteSnapshot(turn.response)" size="small" round :icon="Document"
                      :disabled="loading" @click="saveNote(turn)">
                      保存为私有实验笔记
                    </el-button>
                    <el-button v-if="draftOf(turn.response)" size="small" type="primary" plain round
                      :disabled="loading" @click="openDraft(turn.response.structured.evidence.draft)">
                      打开参数草案
                    </el-button>
                    <span v-if="draftOf(turn.response)" class="action-note">只回填现有任务表单，助手不会创建任务。</span>
                  </div>
                </div>
              </div>
            </article>
          </section>

          <form class="assistant-composer" @submit.prevent="ask">
            <div class="composer-head">
              <div>
                <span class="composer-kicker">ASK WITH EVIDENCE</span>
                <strong>描述你的实验问题</strong>
              </div>
              <span class="composer-shortcut">Ctrl + Enter 发送</span>
            </div>
            <el-input v-model="question" type="textarea" :rows="3" maxlength="500" show-word-limit
              class="composer-input" aria-label="实验问题"
              placeholder="例如：对比两次运行的清洗纯度与保留样本数，并说明证据缺口。"
              @keydown.ctrl.enter.prevent="ask" />
            <div class="composer-footer">
              <span class="composer-bound"><el-icon><Lock /></el-icon> 只读取当前账号有权访问的报告</span>
              <el-button class="send-button" native-type="submit" type="primary" :icon="Promotion" :loading="loading"
                :disabled="loadingSession || !question.trim()">发送</el-button>
            </div>
          </form>
        </div>
      </div>
    </div>

    <el-drawer v-model="historyOpen" title="对话历史" size="min(100%, 420px)">
      <div v-loading="historyLoading">
        <p v-if="historyError">读取失败 <el-button link type="primary" @click="loadHistory">重试</el-button></p>
        <el-empty v-else-if="!sessions.length && !historyLoading" description="暂无历史会话" />
        <div v-for="session in sessions" :key="session.sessionId" class="history-row">
          <button type="button" class="history-select" @click="useSession(session)">
            <span>{{ session.title }}</span><small>{{ formatDate(session.updatedAt) }}</small>
          </button>
          <el-button :icon="Delete" text aria-label="删除会话" @click="removeSession(session)" />
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="toolsOpen" title="助手工具" size="min(100%, 420px)">
      <div v-loading="toolsLoading">
        <p v-if="toolsError">读取失败 <el-button link type="primary" @click="openTools">重试</el-button></p>
        <div v-for="tool in tools" :key="tool.name" class="tool-entry">
          <strong>{{ toolLabel(tool.name) }}</strong>
          <el-tag size="small" effect="plain">只读</el-tag>
          <p>{{ tool.description }}</p>
        </div>
        <div class="tool-entry">
          <strong>失败诊断</strong>
          <el-tag size="small" effect="plain">任务详情入口</el-tag>
          <p>核对失败任务及该次尝试的记录。</p>
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="notesOpen" title="私有实验笔记" size="min(100%, 520px)">
      <div v-loading="notesLoading">
        <p v-if="notesError">读取失败 <el-button link type="primary" @click="loadNotes">重试</el-button></p>
        <el-empty v-else-if="!notes.length && !notesLoading" description="暂无笔记；在对照结果上点击「保存为私有实验笔记」" />
        <div v-for="note in notes" :key="note.noteId" class="note-entry">
          <strong>{{ note.title }}</strong>
          <small>{{ formatDate(note.analysisTime) }}</small>
          <el-tag v-if="note.accessState && note.accessState !== 'verified'" size="small" type="warning" effect="plain">
            证据已按当前权限复核，部分指标不可见
          </el-tag>
          <p v-if="note.nextStep">{{ note.nextStep }}</p>
          <div>
            <el-button link type="primary" @click="editNote(note)">编辑</el-button>
            <el-button link type="primary" @click="downloadNote(note)">导出 Markdown</el-button>
            <el-button link type="danger" @click="removeNote(note)">删除</el-button>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight, ChatDotRound, CircleCheck, Connection, DataAnalysis, Delete, Document,
  Lock, MagicStick, Plus, Promotion, Search, Tools, Warning
} from '@element-plus/icons-vue'
import {
  chatWithAssistant, createExperimentNote, deleteAssistantSession, deleteExperimentNote,
  exportExperimentNote, getAssistantSession, getAssistantSessions, getAssistantTools,
  getExperimentNotes, updateExperimentNote
} from '../api/assistant'

const assistantScope = 'experiment_assistant'
const route = useRoute()
const router = useRouter()
const question = ref('')
const turns = ref([])
const loading = ref(false)
const sessionId = ref(null)
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyError = ref(false)
const sessions = ref([])
const loadingSession = ref(false)
const conversation = ref(null)
const toolsOpen = ref(false)
const toolsLoading = ref(false)
const toolsError = ref(false)
const tools = ref([])
const notesOpen = ref(false)
const notesLoading = ref(false)
const notesError = ref(false)
const notes = ref([])
const noteRequests = new WeakMap()
let generation = 0
let historyRequest = 0
let sessionRequest = 0
const taskId = computed(() => {
  const value = route.query.taskId
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) ? value : null
})
const failureContext = computed(() => taskId.value && route.query.source !== 'report' ? {
  taskId: taskId.value,
  runId: route.query.runId || null,
  attempt: route.query.attempt || null
} : null)
const experimentContext = computed(() => taskId.value && route.query.source === 'report' ? {
  taskId: taskId.value,
  runId: typeof route.query.runId === 'string' ? route.query.runId : null,
  attempt: Number(route.query.attempt) || null
} : null)
const headerContextLabel = computed(() => {
  if (experimentContext.value) return '实验报告'
  return failureContext.value?.attempt ? `第 ${failureContext.value.attempt} 次尝试` : '失败诊断'
})

const starterPrompts = [
  {
    icon: DataAnalysis,
    title: '对照两次运行',
    description: '比较纯度与样本数。',
    prompt: '帮我选择两次实验运行，按 60% 保留比例对照清洗纯度和保留样本数。'
  },
  {
    icon: Search,
    title: '筛选可导出子集',
    description: '筛选可导出档位。',
    prompt: '按至少 2 万张保留样本筛选可导出子集，并说明每档的导出状态。'
  },
  {
    icon: MagicStick,
    title: '生成下一轮草案',
    description: '只改轮数并核对配置。',
    prompt: '下一轮只改训练轮数，帮我生成参数草案并核对配置差异。'
  },
  {
    icon: Document,
    title: '解读实验报告',
    description: '解释指标与证据缺口。',
    prompt: '解读最近一次实验报告，说明清洗纯度、保留样本数和证据缺口。'
  }
]

function toolLabel(name) {
  return {
    search_knowledge: '知识检索',
    explain_clean_purity: '清洗纯度口径',
    compare_experiment_runs: '运行对照',
    list_my_tasks: '本人任务查询',
    diagnose_task_failure: '失败诊断',
    get_my_task_summary: '任务摘要',
    get_my_report_summary: '报告摘要',
    validate_ldd_parameters: '参数校验',
    get_parameter_guide: '参数指南',
    preview_create_task: '创建任务预览',
    preview_start_task: '启动任务预览'
  }[name] || name
}

function formatDate(value) {
  const date = new Date(value)
  return value && !Number.isNaN(date.getTime())
    ? date.toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : ''
}

async function scrollToLatest() {
  await nextTick()
  if (conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight
}

async function send(value, context) {
  const requestGeneration = generation
  turns.value.push({ question: value, response: null, error: '' })
  const index = turns.value.length - 1
  loading.value = true
  scrollToLatest()
  try {
    const { data } = await chatWithAssistant(
      value, sessionId.value, assistantScope,
      context?.failureContext || null,
      context?.experimentContext || null
    )
    loadHistory()
    if (requestGeneration !== generation) return
    turns.value[index].response = {
      ...data,
      taskChoices: data.structured?.evidence?.taskList?.tasks || []
    }
    sessionId.value = data.sessionId || sessionId.value
  } catch (error) {
    if (requestGeneration !== generation) return
    turns.value[index].error = error.response?.data?.message || '助手请求失败，请稍后重试。'
  } finally {
    if (requestGeneration === generation) {
      loading.value = false
      scrollToLatest()
    }
  }
}

function ask() {
  const value = question.value.trim()
  if (loading.value || loadingSession.value || !value) return
  question.value = ''
  send(value)
}

function askStarter(prompt) {
  if (loading.value || loadingSession.value) return
  question.value = ''
  send(prompt)
}

function answerStatusText(response) {
  if (!response) return '处理中'
  if (response.status === 'needs_context') return '等待选择运行'
  if (response.status === 'stale') return '原选择已保留'
  if (response.status === 'answered') {
    return response.answerSource === 'server_evidence' ? '证据已核对' : '已回答'
  }
  if (response.modelStatus === 'model_disabled') return '确定性降级'
  return response.status || '已返回'
}

function chooseTask(task, action, turn) {
  if (loading.value || loadingSession.value) return
  if (action === 'COMPARE') {
    const response = turn?.response
    const selection = response?.structured?.evidence?.taskList?.selection
    const primary = selection?.primaryTaskId
      || response?.answer?.match(/任务 #([1-9]\d{0,18})/)?.[1]
      || (taskId.value && taskId.value !== task.taskId ? taskId.value : null)
    if (!primary || String(primary) === String(task.taskId)) {
      ElMessage.warning('请先明确主运行，再选择对照运行。')
      return
    }
    const ratio = selection?.ratio
      || turn?.question?.match(/(\d{1,3})%/)?.[1]
      || '30'
    const runs = [{
      taskId: String(primary),
      runId: selection?.primaryRunId || experimentContext.value?.runId || null,
      attempt: selection?.primaryAttempt || experimentContext.value?.attempt || null
    }, {
      taskId: String(task.taskId),
      runId: task.runId || null,
      attempt: task.attempt || null
    }]
    send(`对比任务 #${primary} 和 #${task.taskId}，保留比例 ${ratio}%`, {
      experimentContext: { runs, ratio: Number(ratio) }
    })
    return
  }
  send(action === 'TASK' ? `查询任务 #${task.taskId} 的进度` : `解读任务 #${task.taskId} 的报告`)
}

function resetConversation() {
  generation++
  sessionRequest++
  turns.value = []
  sessionId.value = null
  question.value = ''
  loading.value = false
  loadingSession.value = false
}

function newConversation() {
  resetConversation()
  if (taskId.value) router.replace({ path: '/assistant' })
}

async function loadHistory() {
  const request = ++historyRequest
  historyLoading.value = true
  historyError.value = false
  try {
    const { data } = await getAssistantSessions()
    if (request === historyRequest) sessions.value = data || []
  } catch {
    if (request === historyRequest) historyError.value = true
  } finally {
    if (request === historyRequest) historyLoading.value = false
  }
}

async function useSession(session) {
  if (taskId.value) await router.replace({ path: '/assistant' })
  const request = ++sessionRequest
  loadingSession.value = true
  historyOpen.value = false
  try {
    const { data } = await getAssistantSession(session.sessionId)
    if (request !== sessionRequest) return
    resetConversation()
    sessionId.value = data.sessionId
    turns.value = (data.summary?.turns || []).map(turn => ({
      question: turn.question,
      response: {
        answer: turn.answer,
        toolResults: turn.toolResults || (turn.tools || []).map(toolName => ({ toolName }))
      }
    }))
    scrollToLatest()
  } catch (error) {
    if (request === sessionRequest) ElMessage.error(error.response?.data?.message || '历史会话读取失败')
  } finally {
    if (request === sessionRequest) loadingSession.value = false
  }
}

function draftOf(response) {
  const draft = response?.structured?.evidence?.draft
  return response?.status === 'answered' && draft?.proposedParams ? draft : null
}

function openDraft(draft) {
  if (!draft?.taskId) return
  sessionStorage.setItem('ea-draft-prefill', JSON.stringify({ taskId: draft.taskId, draft }))
  router.push(draft.formPath || `/tasks?prefillTaskId=${draft.taskId}`)
}

function noteSnapshot(response) {
  if (response?.status !== 'answered') return null
  const evidence = response?.structured?.evidence || {}
  if (evidence.comparison?.runs?.length) {
    return {
      runRefs: evidence.comparison.runs.map(run => ({
        taskId: run.taskId, runId: run.runId, attempt: run.attempt, ratio: evidence.comparison.ratio
      })),
      title: `实验对照 ${evidence.comparison.runs.map(run => `#${run.taskId}`).join(' vs ')}`
    }
  }
  if (evidence.subset?.available?.length || evidence.subset?.taskId) {
    return {
      runRefs: [{
        taskId: evidence.subset.taskId, runId: evidence.subset.runId || null,
        attempt: evidence.subset.attempt || null, ratio: evidence.subset.recommendedRatio
      }],
      title: `保留比例筛选 任务 #${evidence.subset.taskId}`
    }
  }
  return null
}

async function saveNote(turn) {
  const payload = noteSnapshot(turn.response)
  if (!payload) return
  let title
  try {
    const result = await ElMessageBox.prompt('笔记只对当前账号可见，保存的是分析时快照。', '保存实验笔记', {
      inputValue: payload.title, inputPattern: /\S+/, inputErrorMessage: '标题不能为空',
      confirmButtonText: '保存', cancelButtonText: '取消'
    })
    title = result.value
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  let requestKey = noteRequests.get(turn)
  if (!requestKey) {
    requestKey = (globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`).replace(/-/g, '').slice(0, 64)
    noteRequests.set(turn, requestKey)
  }
  try {
    await createExperimentNote({
      title,
      nextStep: '复核证据缺口和条件差异后，再决定下一轮配置。',
      requestKey,
      runRefs: payload.runRefs
    })
    noteRequests.delete(turn)
    ElMessage.success('笔记已保存')
    if (notesOpen.value) loadNotes()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '笔记保存失败')
  }
}

async function openNotes() {
  notesOpen.value = true
  await loadNotes()
}

async function loadNotes() {
  notesLoading.value = true
  notesError.value = false
  try {
    const { data } = await getExperimentNotes()
    notes.value = data || []
  } catch {
    notesError.value = true
  } finally {
    notesLoading.value = false
  }
}

async function downloadNote(note) {
  try {
    const { data } = await exportExperimentNote(note.noteId)
    const url = URL.createObjectURL(new Blob([data], { type: 'text/markdown;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${note.title || '实验笔记'}.md`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '笔记导出失败')
  }
}

async function removeNote(note) {
  try {
    await ElMessageBox.confirm('删除后该私有笔记不可恢复。', '删除笔记', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
    await deleteExperimentNote(note.noteId)
    notes.value = notes.value.filter(item => item.noteId !== note.noteId)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '笔记删除失败')
  }
}

function openCitation(citation) {
  if (!citation?.targetPath) return
  router.push(citation.targetPath)
}

async function editNote(note) {
  try {
    const titleResult = await ElMessageBox.prompt('修改笔记标题', '编辑私有笔记', {
      inputValue: note.title, inputPattern: /\S+/, inputErrorMessage: '标题不能为空',
      confirmButtonText: '下一步', cancelButtonText: '取消'
    })
    const stepResult = await ElMessageBox.prompt('修改下一步备注（可留空）', '编辑私有笔记', {
      inputValue: note.nextStep || '', inputType: 'textarea',
      confirmButtonText: '保存', cancelButtonText: '取消'
    })
    await updateExperimentNote(note.noteId, {
      title: titleResult.value,
      nextStep: stepResult.value || ''
    })
    ElMessage.success('笔记已更新')
    await loadNotes()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '笔记更新失败')
  }
}

async function openTools() {
  toolsOpen.value = true
  if (toolsLoading.value || tools.value.length) return
  toolsLoading.value = true
  toolsError.value = false
  try {
    const { data } = await getAssistantTools(assistantScope)
    tools.value = data || []
  } catch {
    toolsError.value = true
  } finally {
    toolsLoading.value = false
  }
}

async function removeSession(session) {
  try {
    await ElMessageBox.confirm('删除后该会话摘要不可恢复。', '删除会话', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
    await deleteAssistantSession(session.sessionId)
    sessions.value = sessions.value.filter(item => item.sessionId !== session.sessionId)
    if (sessionId.value === session.sessionId) resetConversation()
    loadHistory()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.response?.data?.message || '会话删除失败')
  }
}

watch(failureContext, context => {
  if (route.path !== '/assistant') return
  resetConversation()
  if (context) send('分析这次实验失败的原因和下一步检查项', { failureContext: context })
}, { immediate: true })

watch(experimentContext, context => {
  if (route.path !== '/assistant' || !context) return
  resetConversation()
  send(`以任务 #${context.taskId} 为主运行，帮我选择第二次运行并按相同保留比例对照清洗纯度和样本数。`, {
    experimentContext: {
      runs: [{ taskId: context.taskId, runId: context.runId || null, attempt: context.attempt || null }],
      ratio: 30
    }
  })
}, { immediate: true })

onMounted(loadHistory)
onUnmounted(() => { generation++; historyRequest++; sessionRequest++ })
</script>

<style scoped>
.experiment-assistant {
  --ea-navy: #0c2138;
  --ea-ink: #273b50;
  --ea-muted: #708398;
  --ea-line: #dfe9f4;
  --ea-blue: #2563eb;
  --ea-soft: #f4f9ff;
  width: 100%;
  color: var(--ea-ink);
}

/* Hero */
.assistant-hero {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 18px;
  min-height: 122px;
  padding: 22px 24px;
  margin-bottom: 20px;
  border: 1px solid #d9e7f6;
  border-radius: 24px;
  background:
    radial-gradient(650px 220px at 100% -30%, rgba(37, 99, 235, .17), transparent 70%),
    linear-gradient(135deg, #ffffff 0%, #f3f8ff 58%, #eef6ff 100%);
  box-shadow: 0 24px 56px -48px rgba(12, 33, 56, .55);
}
.assistant-hero::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: -1;
  background-image:
    linear-gradient(rgba(37, 99, 235, .06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(37, 99, 235, .06) 1px, transparent 1px);
  background-size: 30px 30px;
  mask-image: linear-gradient(90deg, transparent 32%, #000 100%);
  opacity: .85;
  pointer-events: none;
}
.hero-emblem {
  position: relative;
  z-index: 1;
  flex: 0 0 62px;
  width: 62px;
  height: 62px;
  display: grid;
  place-items: center;
  border-radius: 19px;
  background: linear-gradient(145deg, #0d2945, #1b4b78 60%, #26689b);
  color: #fff;
  font-size: 18px;
  font-weight: 800;
  letter-spacing: .08em;
  box-shadow: 0 14px 28px -16px rgba(13, 41, 69, .7), inset 0 1px 0 rgba(255, 255, 255, .22);
}
.hero-emblem i {
  position: absolute;
  top: 9px;
  right: 9px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #34d399;
  box-shadow: 0 0 0 4px rgba(52, 211, 153, .16);
}
.hero-copy { flex: 1; min-width: 0; position: relative; z-index: 1; }
.hero-eyebrow {
  display: block;
  margin-bottom: 4px;
  color: #2f80ed;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1.6px;
}
.hero-copy h2 {
  margin: 0 0 6px;
  color: var(--ea-navy);
  font-size: 28px;
  line-height: 1.2;
  letter-spacing: -.03em;
}
.hero-copy p {
  margin: 0;
  max-width: 540px;
  color: #667b92;
  font-size: 12.5px;
  line-height: 1.65;
}
.hero-side {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
}
.hero-signals { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.signal-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  border: 1px solid #dbe7f3;
  border-radius: 999px;
  background: rgba(255, 255, 255, .8);
  color: #486983;
  font-size: 10.5px;
  white-space: nowrap;
}
.signal-pill.is-live { color: #0f766e; background: #f0fdfa; border-color: #ccfbf1; }
.signal-pill.is-live i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #14b8a6;
  box-shadow: 0 0 0 3px rgba(20, 184, 166, .12);
  animation: ea-pulse 2.2s ease-in-out infinite;
}
.assistant-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.assistant-actions :deep(.el-button) {
  margin-left: 0;
  border-radius: 11px;
  font-size: 12px;
}
.assistant-actions :deep(.el-button--primary) {
  box-shadow: 0 8px 18px -12px rgba(37, 99, 235, .8);
}
.assistant-actions .mobile-history { display: none; }

/* Layout */
.assistant-layout {
  display: grid;
  grid-template-columns: 234px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

/* History */
.history-sidebar {
  position: sticky;
  top: 16px;
  min-width: 0;
  padding: 14px 11px;
  border: 1px solid var(--ea-line);
  border-radius: 20px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  box-shadow: 0 18px 40px -42px rgba(12, 33, 56, .4);
}
.sidebar-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 6px 0;
}
.sidebar-eyebrow {
  display: block;
  margin-bottom: 3px;
  color: #3d7fc1;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 1.4px;
}
.sidebar-heading h3 { margin: 0; color: #173557; font-size: 14px; line-height: 1.3; }
.sidebar-count {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  height: 24px;
  padding: 0 7px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2f6db1;
  font-size: 10px;
  font-weight: 700;
}
.sidebar-hint {
  margin: 8px 6px 12px;
  color: #899bad;
  font-size: 10.5px;
  line-height: 1.55;
}
.history-list {
  max-height: min(600px, calc(100dvh - 360px));
  overflow-y: auto;
  padding: 3px;
}
.history-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 30px;
  align-items: center;
  margin-bottom: 5px;
  border: 1px solid transparent;
  border-radius: 12px;
  transition: background .18s ease, border-color .18s ease, box-shadow .18s ease;
}
.history-row:hover { background: #f7fbff; border-color: #e4edf7; }
.history-row.active {
  background: linear-gradient(135deg, #eff6ff, #f8fbff);
  border-color: #cfe1f5;
  box-shadow: inset 3px 0 0 #2563eb, 0 8px 16px -18px rgba(37, 99, 235, .4);
}
.history-row :deep(.el-button) { width: 30px; padding-inline: 0; color: #9aaabc; opacity: .65; }
.history-row:hover :deep(.el-button), .history-row.active :deep(.el-button) { opacity: 1; color: #6f8398; }
.history-select {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 9px 8px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.history-title {
  display: block;
  overflow: hidden;
  color: #334a61;
  font-weight: 600;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-row.active .history-title { color: #174ea6; }
.history-select small { color: #93a4b6; font-size: 10px; }
.history-select:focus-visible { outline: 2px solid #93c5fd; outline-offset: 2px; border-radius: 9px; }

/* Main and context */
.assistant-main { min-width: 0; display: flex; flex-direction: column; }
.diagnostic-context {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid #e2ebf4;
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 12px 26px -28px rgba(12, 33, 56, .35);
}
.context-mark {
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #eff6ff;
  color: #2f6db1;
}
.context-copy { min-width: 0; }
.context-copy span { display: block; margin-bottom: 2px; color: #8b9db0; font-size: 9px; letter-spacing: 1.1px; }
.context-copy strong { display: block; overflow-wrap: anywhere; color: #274461; font-size: 12px; }
.context-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: auto;
  color: #2364a8;
  font-size: 11px;
  text-decoration: none;
  white-space: nowrap;
}
.context-link:hover { color: #174ea6; }
.context-link .el-icon { font-size: 12px; }

/* Chat shell */
.chat-shell {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ea-line);
  border-radius: 24px;
  background: #fff;
  box-shadow: 0 30px 70px -64px rgba(12, 33, 56, .55);
}
.chat-shell-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px solid #edf2f8;
  background: linear-gradient(90deg, #f8fbff, #ffffff);
}
.chat-identity { display: flex; align-items: center; gap: 9px; min-width: 0; }
.live-dot {
  flex: 0 0 7px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, .1);
  animation: ea-pulse 2.2s ease-in-out infinite;
}
.chat-identity strong { display: block; color: #1e3b59; font-size: 12.5px; }
.chat-identity small { display: block; margin-top: 2px; color: #8c9eb1; font-size: 10px; }
.scope-tag {
  flex: 0 0 auto;
  padding: 5px 9px;
  border: 1px solid #dce9f7;
  border-radius: 999px;
  background: #f4f9ff;
  color: #3972ab;
  font-size: 10px;
  letter-spacing: .04em;
}
.conversation {
  min-height: 340px;
  max-height: max(340px, calc(100dvh - 530px));
  overflow-y: auto;
  padding: 18px 20px 8px;
  background:
    radial-gradient(circle at 100% 0, rgba(37, 99, 235, .035), transparent 40%),
    #fff;
  scroll-behavior: smooth;
}

/* Welcome */
.assistant-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 720px;
  margin: 8px auto 4px;
  text-align: center;
}
.welcome-emblem {
  width: 60px;
  height: 60px;
  display: grid;
  place-items: center;
  border-radius: 21px;
  background: linear-gradient(145deg, #0d2945, #20527f);
  color: #fff;
  font-size: 19px;
  font-weight: 800;
  letter-spacing: .08em;
  box-shadow: 0 0 0 7px rgba(37, 99, 235, .055), 0 0 0 14px rgba(37, 99, 235, .025), 0 18px 30px -22px rgba(13, 41, 69, .7);
}
.welcome-kicker {
  margin-top: 16px;
  color: #2f80ed;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 1.5px;
}
.assistant-welcome h3 {
  margin: 6px 0 4px;
  color: var(--ea-navy);
  font-size: 21px;
  letter-spacing: -.02em;
}
.welcome-subtitle {
  max-width: 480px;
  margin: 0;
  color: #75889c;
  font-size: 11.5px;
  line-height: 1.65;
}
.starter-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
  margin-top: 16px;
  text-align: left;
}
.starter-card {
  display: grid;
  grid-template-columns: 33px minmax(0, 1fr) 15px;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #e1ebf5;
  border-radius: 13px;
  background: rgba(255, 255, 255, .92);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease, background .18s ease;
}
.starter-card:hover:not(:disabled) {
  transform: translateY(-1px);
  border-color: #bad6f2;
  background: #fff;
  box-shadow: 0 14px 24px -20px rgba(37, 99, 235, .45);
}
.starter-card:disabled { opacity: .55; cursor: not-allowed; }
.starter-icon {
  width: 33px;
  height: 33px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 16px;
}
.starter-copy { min-width: 0; }
.starter-copy strong { display: block; color: #214263; font-size: 11.5px; }
.starter-copy small { display: block; margin-top: 2px; color: #8496a9; font-size: 9.5px; line-height: 1.45; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.starter-arrow { color: #9fb5c9; font-size: 12px; transition: transform .18s ease, color .18s ease; }
.starter-card:hover:not(:disabled) .starter-arrow { transform: translateX(2px); color: #2563eb; }
.trust-strip {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 7px 13px;
  width: 100%;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #e6edf6;
  color: #7d90a4;
  font-size: 9px;
}
.trust-strip span { display: inline-flex; align-items: center; gap: 4px; }
.trust-strip .el-icon { color: #4f90d2; font-size: 10px; }

/* Conversation */
.conversation-turn { display: flex; flex-direction: column; gap: 13px; padding: 7px 0 18px; }
.conversation-turn + .conversation-turn { margin-top: 2px; padding-top: 19px; border-top: 1px solid #edf2f8; }
.user-message { display: flex; align-items: flex-start; justify-content: flex-end; gap: 8px; }
.user-bubble {
  max-width: 84%;
  padding: 11px 15px;
  border-radius: 16px 16px 5px 16px;
  background: linear-gradient(135deg, #143a62, #245b8d);
  box-shadow: 0 12px 22px -18px rgba(20, 58, 98, .7);
}
.user-question {
  margin: 0;
  color: #f6faff;
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.assistant-message { display: flex; align-items: flex-start; gap: 9px; }
.assistant-avatar {
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  background: linear-gradient(145deg, #0d2945, #2b628f);
  color: #fff;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: .05em;
  box-shadow: 0 8px 16px -14px rgba(13, 41, 69, .6);
}
.assistant-body {
  flex: 1;
  min-width: 0;
  padding: 13px 15px 14px;
  border: 1px solid #e3ebf4;
  border-radius: 5px 17px 17px;
  background: linear-gradient(180deg, #fbfdff, #f7fbff);
  box-shadow: 0 10px 24px -24px rgba(12, 33, 56, .35);
}
.message-meta { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin-bottom: 8px; }
.message-meta strong { color: #17385b; font-size: 11px; letter-spacing: .02em; }
.reply-state {
  padding: 3px 7px;
  border: 1px solid #dce9f6;
  border-radius: 999px;
  background: #f5faff;
  color: #3d75a8;
  font-size: 9px;
}
.reply-state.is-answered { color: #0f766e; border-color: #ccefe9; background: #f0fdfa; }
.reply-state.is-needs_context, .reply-state.is-stale { color: #9a6700; border-color: #f2e0b6; background: #fffbeb; }
.reply-state.is-degraded, .reply-state.is-model_disabled { color: #64748b; border-color: #e2e8f0; background: #f8fafc; }
.tool-count { margin-left: auto; color: #8296aa; font-size: 9.5px; }
.answer-text {
  margin: 0;
  color: #304457;
  font-size: 13px;
  line-height: 1.82;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.processing-text {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: #6e8398;
  font-size: 12px;
}
.thinking-dots { display: inline-flex; gap: 3px; }
.thinking-dots i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #60a5fa;
  animation: ea-blink 1.2s infinite ease-in-out;
}
.thinking-dots i:nth-child(2) { animation-delay: .18s; }
.thinking-dots i:nth-child(3) { animation-delay: .36s; }

/* Candidate cards */
.task-choices { display: grid; gap: 7px; margin: 12px 0 0; padding: 0; list-style: none; }
.task-choice {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px 9px;
  border: 1px solid #dce8f4;
  border-radius: 11px;
  background: #fff;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, transform .18s ease;
}
.task-choice:hover:not(:disabled) { transform: translateX(2px); border-color: #9ac7ee; background: #f5faff; }
.task-choice:disabled { opacity: .55; cursor: not-allowed; }
.choice-index {
  flex: 0 0 26px;
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: #eaf4ff;
  color: #2f7fd1;
  font-size: 9px;
  font-weight: 700;
}
.choice-copy { min-width: 0; }
.choice-copy strong { display: block; overflow: hidden; color: #284864; font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.choice-copy small { display: block; margin-top: 2px; color: #8ca0b3; font-size: 9.5px; }
.choice-arrow { margin-left: auto; color: #a6bed3; font-size: 13px; }

/* Tool activity */
.tool-activity {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #dfeaf5;
  color: #536a81;
  font-size: 10.5px;
}
.tool-activity summary {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #215d99;
  font-weight: 650;
  cursor: pointer;
  list-style: none;
}
.tool-activity summary::-webkit-details-marker { display: none; }
.tool-summary-icon {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  background: #eff6ff;
  color: #2f6db1;
  font-size: 12px;
}
.tool-activity summary b { color: #6f88a2; font-size: 9px; font-weight: 600; }
.tool-activity ul { margin: 8px 0 0; padding: 0; list-style: none; }
.tool-activity li { padding: 8px 0; border-top: 1px solid #eef3f8; overflow-wrap: anywhere; }
.tool-activity li:first-child { border-top: 0; }
.tool-activity li strong { color: #30465d; font-size: 10.5px; }
.tool-activity li span { margin-left: 8px; color: #8195a8; font-size: 9px; }
.tool-activity .tool-failed { color: var(--el-color-danger-dark-2); }
.tool-activity p, .tool-entry p { margin: 5px 0 0; line-height: 1.6; }

/* Evidence */
.evidence-block {
  margin-top: 12px;
  overflow: hidden;
  border: 1px solid #e4edf6;
  border-radius: 12px;
  background: rgba(255, 255, 255, .75);
}
.evidence-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 11px;
  border-bottom: 1px solid #e7eff8;
  background: #f4f9ff;
  color: #2f5e8d;
  font-size: 10px;
}
.evidence-head span { display: inline-flex; align-items: center; gap: 5px; font-weight: 650; }
.evidence-head small { color: #8298ad; font-size: 9px; }
.citations { margin: 0; border: 0; --el-collapse-header-height: 38px; }
.citations :deep(.el-collapse-item__header) {
  padding: 0 11px;
  border-bottom-color: #eef3f8;
  background: transparent;
  color: #35536f;
  font-size: 10.5px;
  font-weight: 550;
  line-height: 1.4;
  height: auto;
  min-height: 38px;
}
.citations :deep(.el-collapse-item__wrap) { border-bottom-color: #eef3f8; background: transparent; }
.citations :deep(.el-collapse-item__content) { padding: 9px 11px 11px; color: #536a81; }
.citation-source { display: block; margin-bottom: 6px; color: #8499ad; font-size: 9.5px; overflow-wrap: anywhere; }
.citations :deep(.el-button) { padding: 0; font-size: 10px; }

/* Result actions */
.result-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px dashed #e2ebf4;
}
.result-actions :deep(.el-button) { margin-left: 0; font-size: 11px; }
.action-note { color: #7d90a4; font-size: 9.5px; }

/* Composer */
.assistant-composer {
  padding: 13px 18px 15px;
  border-top: 1px solid #e7eff8;
  background: linear-gradient(180deg, #fbfdff, #f7fbff);
}
.composer-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.composer-kicker { display: block; margin-bottom: 2px; color: #2f80ed; font-size: 8.5px; font-weight: 750; letter-spacing: 1.4px; }
.composer-head strong { color: #183a5a; font-size: 12px; }
.composer-shortcut { color: #8fa2b5; font-size: 9.5px; }
.composer-input :deep(.el-textarea__inner) {
  padding: 11px 13px;
  border: 1px solid #dbe7f4;
  border-radius: 14px;
  background: #fff;
  box-shadow: inset 0 1px 2px rgba(16, 42, 67, .03), 0 4px 12px -12px rgba(16, 42, 67, .4);
  color: #304457;
  font-size: 12.5px;
  line-height: 1.7;
  resize: none;
  transition: border-color .18s ease, box-shadow .18s ease;
}
.composer-input :deep(.el-textarea__inner:focus) {
  border-color: #8bbcf0;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, .08);
}
.composer-footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 9px; }
.composer-bound { display: inline-flex; align-items: center; gap: 4px; color: #8498ab; font-size: 9.5px; line-height: 1.5; }
.composer-bound .el-icon { color: #4f90d2; font-size: 10px; }
.send-button {
  min-width: 88px;
  height: 34px;
  margin-left: auto;
  border: 0;
  border-radius: 11px;
  background: linear-gradient(135deg, #2563eb, #1f79c9);
  box-shadow: 0 10px 20px -14px rgba(37, 99, 235, .85);
  font-size: 12px;
}
.send-button:hover { background: linear-gradient(135deg, #1d4ed8, #176bb4); }

/* Drawer entries */
.tool-entry {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-light);
  overflow-wrap: anywhere;
}
.tool-entry .el-tag { margin-left: 8px; }
.note-entry { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
.note-entry small { display: block; margin-top: 4px; color: #66758a; }
.note-entry p { margin: 6px 0; line-height: 1.6; }

@keyframes ea-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: .65; transform: scale(.88); }
}
@keyframes ea-blink {
  0%, 100% { opacity: .35; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-2px); }
}

@media (max-width: 1000px) {
  .assistant-actions .mobile-history { display: inline-flex; }
  .assistant-layout { grid-template-columns: minmax(0, 1fr); }
  .history-sidebar { display: none; }
  .assistant-hero { align-items: flex-start; }
  .hero-side { align-self: stretch; justify-content: space-between; }
}

@media (max-width: 760px) {
  .assistant-hero { flex-wrap: wrap; gap: 14px; padding: 18px; }
  .hero-emblem { flex-basis: 52px; width: 52px; height: 52px; border-radius: 16px; font-size: 15px; }
  .hero-copy { flex-basis: calc(100% - 70px); }
  .hero-copy h2 { font-size: 24px; }
  .hero-side { width: 100%; align-items: flex-start; }
  .hero-signals { justify-content: flex-start; }
  .assistant-actions { justify-content: flex-start; }
  .conversation { padding-inline: 14px; }
  .starter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
  .starter-card { grid-template-columns: 30px minmax(0, 1fr) 12px; gap: 7px; padding: 8px 9px; }
  .starter-icon { width: 30px; height: 30px; border-radius: 9px; font-size: 14px; }
  .starter-copy strong { font-size: 11px; }
  .starter-copy small { display: none; }
  .starter-arrow { font-size: 11px; }
}

@media (max-width: 640px) {
  .assistant-hero { padding: 16px; border-radius: 20px; }
  .hero-copy p { font-size: 11.5px; }
  .assistant-actions :deep(.el-button) { padding-inline: 10px; }
  .diagnostic-context { align-items: flex-start; flex-wrap: wrap; }
  .context-link { margin-left: 42px; }
  .chat-shell { border-radius: 20px; }
  .assistant-body { padding: 11px 12px 13px; }
  .user-bubble { max-width: 91%; }
  .message-meta .tool-count { width: 100%; margin-left: 0; }
  .composer-head { align-items: flex-start; flex-direction: column; gap: 3px; }
  .composer-footer { align-items: flex-start; flex-wrap: wrap; }
  .send-button { width: 100%; margin-left: 0; }
}
</style>
