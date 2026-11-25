<template>
  <div class="page experiment-assistant">
    <header class="assistant-heading">
      <div>
        <h2>实验助手</h2>
      </div>
      <div class="assistant-actions">
        <el-button :icon="Plus" @click="newConversation">新对话</el-button>
        <el-button class="mobile-history" :icon="ChatDotRound" @click="historyOpen = true">历史</el-button>
        <el-button :icon="Tools" @click="openTools">工具</el-button>
        <el-button :icon="Document" @click="openNotes">笔记</el-button>
      </div>
    </header>

    <div class="assistant-layout">
      <aside class="history-sidebar" aria-label="对话历史">
        <h3>对话历史</h3>
        <div class="history-list" v-loading="historyLoading">
          <p v-if="historyError" class="muted">读取失败 <el-button link type="primary" @click="loadHistory">重试</el-button></p>
          <p v-else-if="!sessions.length && !historyLoading" class="muted">暂无历史会话</p>
          <div v-for="session in sessions" :key="session.sessionId" class="history-row"
            :class="{ active: session.sessionId === sessionId }">
            <button type="button" class="history-select" :aria-current="session.sessionId === sessionId ? 'true' : undefined"
              @click="useSession(session)">
              <span>{{ session.title }}</span>
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
      <strong>任务 {{ taskId }} · {{ headerContextLabel }}</strong>
      <router-link :to="{ path: '/tasks', query: { taskId } }">返回任务详情</router-link>
    </section>

    <section ref="conversation" class="conversation" aria-label="实验助手对话" aria-live="polite"
      v-loading="loadingSession">
      <el-empty v-if="!turns.length && !loadingSession" description="暂无消息" />
      <article v-for="(turn, index) in turns" :key="index" class="conversation-turn">
        <p class="user-question">{{ turn.question }}</p>
        <div class="assistant-reply">
          <el-alert v-if="turn.error" type="error" :closable="false" :title="turn.error" />
          <p v-else-if="!turn.response" class="muted">{{ taskId && index === 0 ? '正在核对失败证据…' : '正在处理…' }}</p>
          <p v-else class="answer-text">{{ turn.response.answer }}</p>
          <ul v-if="turn.response?.status === 'needs_context' && turn.response?.taskChoices?.length" class="task-choices">
            <li v-for="task in turn.response.taskChoices" :key="task.taskId">
              <el-button link type="primary" :disabled="loading || loadingSession"
                @click="chooseTask(task, turn.response.structured?.taskChoiceAction, turn)">
                {{ task.taskName || '任务' }} · #{{ task.taskId }}
              </el-button>
            </li>
          </ul>
          <details v-if="turn.response?.toolResults?.length" class="tool-activity">
            <summary><el-icon><Tools /></el-icon> 工具调用（{{ turn.response.toolResults.length }}）</summary>
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
          <el-collapse v-if="turn.response?.citations?.length" class="citations">
            <el-collapse-item v-for="citation in turn.response.citations" :key="citation.citationId"
              :name="citation.citationId" :title="citation.title">
              <span class="muted">{{ citation.sourceKey }} · L{{ citation.startLine }}–{{ citation.endLine }}</span>
              <el-button v-if="citation.targetPath" link type="primary" @click="openCitation(citation)">打开对应报告</el-button>
              <p class="answer-text">{{ citation.snippet }}</p>
            </el-collapse-item>
          </el-collapse>
          <div v-if="noteSnapshot(turn.response)" class="note-actions">
            <el-button size="small" :icon="Document" :disabled="loading" @click="saveNote(turn)">
              保存为私有实验笔记
            </el-button>
          </div>
          <div v-if="draftOf(turn.response)" class="note-actions">
            <el-button size="small" type="primary" plain :disabled="loading" @click="openDraft(turn.response.structured.evidence.draft)">
              打开参数草案
            </el-button>
            <span class="muted">只回填现有任务表单，助手不会创建任务。</span>
          </div>
        </div>
      </article>
    </section>

    <form class="assistant-composer" @submit.prevent="ask">
      <el-input v-model="question" type="textarea" :rows="3" maxlength="500" show-word-limit
        aria-label="实验问题" placeholder="例如：清洗纯度与保留样本数分别说明什么？"
        @keydown.ctrl.enter.prevent="ask" />
      <el-button native-type="submit" type="primary" :loading="loading"
        :disabled="loadingSession || !question.trim()">发送</el-button>
    </form>
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
import { ChatDotRound, Delete, Document, Edit, Plus, Tools } from '@element-plus/icons-vue'
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
.experiment-assistant { width: 100%; }
.assistant-heading, .assistant-actions, .diagnostic-context {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
}
.assistant-heading h2 { margin: 0; font-size: 22px; }
.assistant-actions :deep(.el-button) { margin-left: 0; }
.assistant-actions .mobile-history { display: none; }
.assistant-layout {
  display: grid; grid-template-columns: minmax(190px, 235px) minmax(0, 1fr);
  gap: 24px; margin-top: 18px;
}
.history-sidebar { border-right: 1px solid var(--el-border-color-light); padding-right: 16px; min-width: 0; }
.history-sidebar h3 { margin: 6px 0 12px; font-size: 14px; }
.history-list { max-height: min(640px, 68vh); overflow-y: auto; }
.assistant-main { min-width: 0; display: flex; flex-direction: column; }
.diagnostic-context { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); }
.conversation { min-height: 260px; max-height: max(260px, calc(100dvh - 295px)); overflow-y: auto; }
.conversation-turn { display: grid; gap: 12px; padding-block: 16px; }
.conversation-turn + .conversation-turn { border-top: 1px solid var(--el-border-color-light); }
.user-question {
  justify-self: end; max-width: 85%; margin: 0; padding: 10px 14px; border-radius: 8px;
  background: var(--el-fill-color-light); white-space: pre-wrap; overflow-wrap: anywhere;
}
.assistant-reply { min-width: 0; }
.answer-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.citations { margin-top: 12px; }
.task-choices { margin: 8px 0; padding: 0; list-style: none; }
.task-choices :deep(.el-button) {
  justify-content: flex-start; height: auto; min-height: 32px; text-align: left;
  white-space: normal; overflow-wrap: anywhere; max-width: 100%;
}
.tool-activity { margin-top: 12px; font-size: 13px; color: #48586d; }
.tool-activity summary { cursor: pointer; width: fit-content; }
.tool-activity summary .el-icon { vertical-align: middle; }
.tool-activity ul { margin: 8px 0 0; padding: 0 0 0 14px; list-style: none; }
.tool-activity li { padding: 8px 0; border-top: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
.tool-activity li span { margin-left: 8px; }
.tool-activity .tool-failed { color: var(--el-color-danger-dark-2); }
.tool-activity p, .tool-entry p { margin: 5px 0 0; line-height: 1.6; }
.tool-entry { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
.note-actions { margin-top: 12px; }
.note-entry { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
.note-entry small { display: block; margin-top: 4px; color: #66758a; }
.note-entry p { margin: 6px 0; line-height: 1.6; }
.tool-entry .el-tag { margin-left: 8px; }
.assistant-composer { display: flex; align-items: flex-end; gap: 12px; padding-block: 12px; }
.assistant-composer :deep(.el-textarea) { min-width: 0; }
.history-row {
  display: grid; grid-template-columns: minmax(0, 1fr) 36px; align-items: center;
  border-radius: 6px; margin-bottom: 4px;
}
.history-row:is(.active, :hover) { background: var(--el-fill-color-light); }
.history-row.active .history-select { color: var(--el-color-primary-dark-2); }
.history-row :deep(.el-button) { width: 36px; padding-inline: 0; }
.history-select {
  display: grid; gap: 3px; padding: 8px; background: transparent; border: 0;
  text-align: left; color: inherit; font: inherit; font-size: 13px; cursor: pointer; min-width: 0;
}
.history-select span { white-space: nowrap; text-overflow: ellipsis; overflow: hidden; }
.history-select small { color: #66758a; }
@media (max-width: 1000px) {
  .assistant-actions .mobile-history { display: inline-flex; }
  .assistant-layout { grid-template-columns: minmax(0, 1fr); }
  .history-sidebar { display: none; }
}
@media (max-width: 640px) {
  .assistant-composer { flex-direction: column; }
  .assistant-composer :deep(.el-textarea) { width: 100%; }
}
</style>
