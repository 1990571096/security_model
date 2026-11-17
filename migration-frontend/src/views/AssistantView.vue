<template>
  <div class="page" v-loading="loading">
    <section class="hero-band assistant-hero">
      <div>
        <span class="eyebrow">R7 · CONTROLLED EXPERIMENT ASSISTANT</span>
        <h2>受控实验助手</h2>
        <p>只读工具先经服务端授权；创建、启动、取消、重试、导出只生成预览和一次性确认凭据，必须由你再次确认后才会执行。</p>
      </div>
      <div class="hero-summary assistant-summary">
        <span>模型状态</span>
        <strong>{{ modelLabel }}</strong>
        <small>{{ modelEnabled ? '已启用适配器' : '未配置外部模型，不伪造回答' }}</small>
      </div>
    </section>

    <section class="content-grid assistant-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div>
              <strong>提出问题</strong>
              <span class="table-caption">最多 500 字；工具调用、模型调用和总时长均有服务端上限</span>
            </div>
            <el-tag type="info" effect="light">{{ status?.provider || 'disabled' }}</el-tag>
          </div>
        </template>
        <div class="assistant-scenario">
          <span class="table-caption">回答模式</span>
          <el-select v-model="scenario" placeholder="通用聊天（不启用场景约束）" clearable class="scenario-select">
            <el-option label="通用聊天（不启用场景约束）" value="" />
            <el-option
              v-for="item in scenarios"
              :key="item.name"
              :label="item.title"
              :value="item.name"
            />
          </el-select>
          <span v-if="selectedScenario" class="table-caption scenario-example">{{ selectedScenario.inputExample }}</span>
        </div>
        <el-input
          v-model="question"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="例如：查看任务 123 的报告摘要，并说明能否导出"
          @keydown.ctrl.enter="ask"
        />
        <div class="assistant-actions">
          <el-button type="primary" :loading="loading" @click="ask">发送</el-button>
          <el-button text @click="clearResult">清空当前结果</el-button>
          <span class="table-caption">今日剩余 {{ status?.quotaRemaining ?? '—' }} / {{ status?.dailyRequestLimit ?? '—' }} 次</span>
        </div>
        <el-alert
          v-if="!modelEnabled"
          class="assistant-alert"
          type="warning"
          :closable="false"
          show-icon
          title="外部模型未配置"
          description="当前只保留受控工具和确认接口；配置 AssistantModel 适配器后才启用自然语言编排。核心任务、报告和知识检索接口不受影响。"
        />
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div><strong>回答与工具结果</strong><span class="table-caption">工具结果只展示给当前登录用户</span></div>
            <el-tag v-if="result" :type="statusType" effect="dark">{{ statusLabel }}</el-tag>
          </div>
        </template>
        <el-empty v-if="!result" description="提交问题后展示回答、引用和工具结果" />
        <template v-else>
          <el-alert
            v-if="result.status === 'model_disabled'"
            type="warning"
            :closable="false"
            show-icon
            title="模型未配置，未执行任何工具"
            :description="result.modelMessage"
          />
          <el-alert
            v-else-if="result.status === 'tool_error' || result.status === 'timeout' || result.status === 'limit_reached'"
            type="error"
            :closable="false"
            show-icon
            :title="failureTitle"
            :description="result.modelMessage"
          />
          <div v-else class="assistant-answer">{{ result.answer }}</div>

          <div v-if="structured" class="assistant-structured">
            <div class="structured-head">
              <strong>服务端权威结果</strong>
              <el-tag size="small" :type="result.answerSource === 'server_evidence' ? 'success' : 'info'" effect="light">
                {{ result.answerSource === 'server_evidence' ? '证据组装' : '降级结果' }}
              </el-tag>
            </div>
            <el-alert
              v-if="structured.failure"
              type="error"
              :closable="false"
              show-icon
              :title="`场景未完成：${structured.failure.code}`"
              :description="structured.failure.message"
            />

            <div v-if="validation" class="evidence-block">
              <div class="evidence-title">
                <strong>参数校验</strong>
                <el-tag size="small" :type="validation.valid ? 'success' : 'danger'" effect="dark">
                  {{ validation.valid ? '通过' : '未通过' }}
                </el-tag>
              </div>
              <p class="table-caption">{{ validation.code }} · {{ validation.message }}</p>
              <p v-if="validation.algorithm || validation.dataset" class="table-caption">
                算法 {{ validation.algorithm?.name || validation.algorithmId || '—' }}
                · 数据集 {{ validation.dataset?.name || validation.datasetId || '—' }}
                · 校验来源 {{ validation.parameterSource }}
              </p>
              <div v-if="parameterRows.length" class="parameter-grid">
                <span v-for="row in parameterRows" :key="row.key" class="parameter-item">
                  <code>{{ row.key }}</code> = {{ row.value }}
                </span>
              </div>
            </div>

            <div v-if="taskEvidence" class="evidence-block">
              <div class="evidence-title">
                <strong>任务状态与阻塞项</strong>
                <el-tag size="small" effect="plain">{{ taskEvidence.status || 'unknown' }}</el-tag>
              </div>
              <p class="table-caption">
                任务 #{{ taskEvidence.taskId }}「{{ taskEvidence.taskName }}」· 进度 {{ taskEvidence.progress ?? 0 }}%
                · 执行器 {{ executorLabel(taskEvidence.executorConfiguration) }}
              </p>
              <div v-if="structured.evidence.blockers?.length" class="evidence-list">
                <span v-for="item in structured.evidence.blockers" :key="item.code" class="evidence-pill danger">
                  {{ item.code }} · {{ item.message }}
                </span>
              </div>
              <div v-if="structured.evidence.warnings?.length" class="evidence-list">
                <span v-for="item in structured.evidence.warnings" :key="item.code" class="evidence-pill warning">
                  {{ item.code }} · {{ item.message }}
                </span>
              </div>
              <p v-if="!structured.evidence.blockers?.length && !structured.evidence.warnings?.length"
                 class="table-caption">服务端未返回阻塞项或提示。</p>
            </div>

            <div v-if="reportEvidence" class="evidence-block">
              <div class="evidence-title">
                <strong>服务端指标</strong>
                <el-tag size="small" effect="plain">{{ reportEvidence.taskStatus || 'unknown' }}</el-tag>
              </div>
              <p v-if="reportEvidence.metrics?.state === 'unavailable'" class="table-caption">
                指标不可用：服务端未采集到可信结果，不展示模型猜测的数值。
              </p>
              <div v-else class="parameter-grid">
                <span class="parameter-item">
                  <code>隔离比例清洗纯度</code> =
                  {{ reportEvidence.metrics?.cleanPurityPercentByIsolationRatio ?? '—' }}%
                </span>
                <span class="parameter-item">
                  <code>隔离比例选取样本数</code> =
                  {{ reportEvidence.metrics?.selectedSampleCountByIsolationRatio ?? '—' }}
                </span>
              </div>
              <p class="table-caption">
                导出：{{ reportEvidence.exportAvailability ? '可用' : '不可用' }}
                <span v-if="reportEvidence.failureSummary"> · 失败摘要：{{ reportEvidence.failureSummary }}</span>
              </p>
            </div>

            <div v-if="structured.evidence.findings?.length" class="evidence-block">
              <div class="evidence-title"><strong>算法目录证据</strong></div>
              <div class="evidence-list">
                <span v-for="item in structured.evidence.findings" :key="item.algorithmId" class="evidence-pill">
                  #{{ item.algorithmId }} {{ item.name }} · {{ item.type }}
                </span>
              </div>
            </div>

            <div v-if="structured.citations?.length" class="evidence-block">
              <div class="evidence-title"><strong>服务端引用</strong></div>
              <div v-for="citation in structured.citations" :key="citation.citationId" class="citation-line">
                <code>{{ citation.citationId }}</code>
                {{ citation.sourceKey }} · L{{ citation.startLine }}-{{ citation.endLine }}
                <p class="table-caption">{{ citation.snippet }}</p>
              </div>
            </div>

            <el-collapse class="structured-raw">
              <el-collapse-item title="查看服务端完整结构" name="structured">
                <pre class="assistant-json">{{ pretty(structured) }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>

          <p class="table-caption assistant-meta">
            状态 {{ result.status }} · 模型 {{ result.modelStatus }} · 模型调用 {{ result.modelCalls }} 次 · 工具调用 {{ result.toolCalls }} 次
          </p>

          <div v-if="result.toolResults?.length" class="assistant-tools-result">
            <div v-for="tool in result.toolResults" :key="`${tool.toolName}-${tool.durationMs}`" class="tool-result">
              <div class="tool-result-head">
                <strong>{{ tool.toolName }}</strong>
                <el-tag size="small" effect="plain">{{ tool.durationMs }} ms</el-tag>
              </div>
              <p class="table-caption">{{ tool.summary }}</p>
              <el-collapse>
                <el-collapse-item title="查看结构化结果" :name="tool.toolName">
                  <pre class="assistant-json">{{ pretty(tool.data) }}</pre>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
        </template>
      </el-card>
    </section>

    <section v-if="result?.pendingConfirmations?.length" class="assistant-pending">
      <el-card v-for="pending in result.pendingConfirmations" :key="pending.confirmationId" shadow="never">
        <div class="pending-head">
          <div>
            <strong>待确认：{{ operationLabel(pending.operation) }}</strong>
            <span class="table-caption">确认凭据 #{{ pending.confirmationId }} · {{ formatTime(pending.expiresAt) }} 前有效</span>
          </div>
          <el-tag type="warning" effect="dark">一次性</el-tag>
        </div>
        <p class="table-caption">{{ pending.preview?.warning || '确认后才会执行；不确认不会写入业务数据。' }}</p>
        <div class="assistant-actions">
          <el-button type="danger" :loading="confirmingId === pending.confirmationId" @click="confirmAction(pending)">
            确认执行
          </el-button>
          <span class="table-caption">令牌只在本页内存中保存，服务端仅存 SHA-256 摘要。</span>
        </div>
      </el-card>
    </section>

    <section v-if="result?.citations?.length" class="assistant-citations">
      <el-card v-for="citation in result.citations" :key="citation.citationId" shadow="never">
        <strong>{{ citation.citationId }} · {{ citation.title }}</strong>
        <span class="table-caption">
          {{ citation.sourceKey }} · L{{ citation.startLine }}-{{ citation.endLine }}
        </span>
        <p class="citation-snippet">{{ citation.snippet }}</p>
      </el-card>
    </section>

    <section class="content-grid assistant-bottom">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div><strong>允许的工具</strong><span class="table-caption">显式 allow-list；写工具只返回预览</span></div>
            <el-tag effect="light">{{ tools.length }} 个</el-tag>
          </div>
        </template>
        <div class="assistant-tool-list">
          <div v-for="tool in tools" :key="tool.name" class="assistant-tool-item">
            <div>
              <strong>{{ tool.name }}</strong>
              <p class="table-caption">{{ tool.description }}</p>
            </div>
            <el-tag size="small" :type="tool.write ? 'warning' : 'success'" effect="light">
              {{ tool.write ? '写操作·需确认' : '只读' }}
            </el-tag>
          </div>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div><strong>我的会话</strong><span class="table-caption">仅本人可见，7 天后清理</span></div>
            <el-button text :loading="loadingSessions" @click="loadSessions">刷新</el-button>
          </div>
        </template>
        <el-empty v-if="!sessions.length" description="暂无会话" />
        <div v-else class="assistant-session-list">
          <div v-for="session in sessions" :key="session.sessionId" class="assistant-session">
            <div>
              <strong>{{ session.title }}</strong>
              <span class="table-caption">{{ formatTime(session.updatedAt) }}</span>
            </div>
            <div>
              <el-button text @click="useSession(session)">继续</el-button>
              <el-button text type="danger" @click="removeSession(session)">删除</el-button>
            </div>
          </div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  chatWithAssistant,
  confirmAssistantAction,
  deleteAssistantSession,
  getAssistantSessions,
  getAssistantScenarios,
  getAssistantStatus,
  getAssistantTools
} from '../api/assistant'

const loading = ref(false)
const loadingSessions = ref(false)
const confirmingId = ref(null)
const question = ref('')
const scenario = ref('')
const result = ref(null)
const status = ref(null)
const tools = ref([])
const scenarios = ref([])
const sessions = ref([])
const sessionId = ref(null)

const modelEnabled = computed(() => Boolean(status.value?.modelEnabled))
const modelLabel = computed(() => modelEnabled.value ? '已配置' : '未配置')
const selectedScenario = computed(() => scenarios.value.find(item => item.name === scenario.value) || null)
const structured = computed(() => result.value?.structured || null)
const structuredEvidence = computed(() => structured.value?.evidence || null)
const validation = computed(() => structuredEvidence.value?.validation || null)
const taskEvidence = computed(() => structuredEvidence.value?.task || null)
const reportEvidence = computed(() => {
  const evidence = structuredEvidence.value
  if (!evidence || !evidence.metrics) return null
  return {
    taskId: evidence.taskId,
    taskStatus: evidence.taskStatus,
    metrics: evidence.metrics,
    exportAvailability: evidence.exportAvailability,
    failureSummary: evidence.failureSummary
  }
})
const parameterRows = computed(() => {
  const params = validation.value?.normalizedParameters
  if (!params || typeof params !== 'object') return []
  return Object.entries(params)
    .filter(([, value]) => typeof value !== 'object')
    .slice(0, 12)
    .map(([key, value]) => ({ key, value }))
})
const statusLabel = computed(() => ({
  answered: '已回答',
  scenario_incomplete: '场景证据不足',
  model_disabled: '模型未配置',
  tool_error: '工具失败',
  timeout: '超时',
  limit_reached: '额度用尽',
  model_unavailable: '模型不可用',
  model_invalid: '模型输出无效'
}[result.value?.status] || '未知状态'))
const statusType = computed(() => ({
  answered: 'success',
  scenario_incomplete: 'danger',
  model_disabled: 'warning',
  tool_error: 'danger',
  timeout: 'danger',
  limit_reached: 'warning',
  model_unavailable: 'danger',
  model_invalid: 'danger'
}[result.value?.status] || 'info'))
const failureTitle = computed(() => ({
  tool_error: '工具调用失败',
  timeout: '助手请求超时',
  limit_reached: '调用额度用尽'
}[result.value?.status] || '助手请求失败'))

async function loadStatus() {
  try {
    const response = await getAssistantStatus()
    status.value = response.data || null
  } catch (error) {
    status.value = null
  }
}

async function loadTools() {
  try {
    const response = await getAssistantTools()
    tools.value = response.data || []
  } catch (error) {
    tools.value = []
  }
}

async function loadScenarios() {
  try {
    const response = await getAssistantScenarios()
    scenarios.value = response.data || []
  } catch (error) {
    scenarios.value = []
  }
}

async function loadSessions() {
  loadingSessions.value = true
  try {
    const response = await getAssistantSessions()
    sessions.value = response.data || []
  } catch (error) {
    sessions.value = []
  } finally {
    loadingSessions.value = false
  }
}

async function ask() {
  const value = question.value.trim()
  if (!value) {
    ElMessage.warning('请先输入问题')
    return
  }
  loading.value = true
  try {
    const response = await chatWithAssistant(value, sessionId.value, scenario.value)
    result.value = response.data || null
    sessionId.value = response.data?.sessionId || sessionId.value
    await Promise.all([loadStatus(), loadSessions()])
  } catch (error) {
    result.value = null
    ElMessage.error(error.response?.data?.message || '助手请求失败')
  } finally {
    loading.value = false
  }
}

async function confirmAction(pending) {
  confirmingId.value = pending.confirmationId
  try {
    const response = await confirmAssistantAction(pending.confirmationId, pending.token)
    ElMessage.success(response.data?.message || '操作已确认并执行')
    result.value = result.value ? {
      ...result.value,
      pendingConfirmations: (result.value.pendingConfirmations || [])
        .filter(item => item.confirmationId !== pending.confirmationId)
    } : null
    await loadSessions()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '确认失败，未执行任何写操作')
  } finally {
    confirmingId.value = null
  }
}

async function removeSession(session) {
  try {
    await ElMessageBox.confirm('删除后该会话摘要不可恢复，确认继续？', '删除会话', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
    await deleteAssistantSession(session.sessionId)
    if (sessionId.value === session.sessionId) {
      sessionId.value = null
      result.value = null
    }
    await loadSessions()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.response?.data?.message || '删除失败')
    }
  }
}

function useSession(session) {
  sessionId.value = session.sessionId
  result.value = null
  ElMessage.info(`已切换到会话 #${session.sessionId}，之后的问题会延续该摘要`)
}

function clearResult() {
  result.value = null
}

function operationLabel(operation) {
  return ({
    create: '创建任务',
    start: '启动任务',
    cancel: '取消任务',
    retry: '重试任务',
    export: '导出清洗子集'
  }[operation] || operation)
}

function executorLabel(value) {
  return ({
    configured: '本机已配置',
    missing: '本机未配置',
    worker_unknown: '需 Worker 校验'
  }[value] || '未返回')
}

function pretty(value) {
  try {
    return JSON.stringify(value, null, 2)
  } catch (error) {
    return String(value)
  }
}

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

onMounted(async () => {
  await Promise.all([loadStatus(), loadTools(), loadScenarios(), loadSessions()])
})
</script>

<style scoped>
.assistant-hero { background: linear-gradient(135deg, #0f2742 0%, #1d4b66 100%); }
.assistant-summary { min-width: 210px; }
.assistant-grid { margin-top: 18px; }
.assistant-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 14px; }
.assistant-alert { margin-top: 14px; }
.assistant-scenario { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.scenario-select { width: 260px; }
.scenario-example { flex: 1; min-width: 220px; }
.assistant-answer { white-space: pre-wrap; line-height: 1.8; font-size: 15px; }
.assistant-structured { margin-top: 16px; padding: 14px; border: 1px solid #dbe5f0; border-radius: 10px; background: #f8fbfe; }
.structured-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.evidence-block { margin-top: 12px; padding-top: 10px; border-top: 1px dashed #e5eaf1; }
.evidence-title { display: flex; align-items: center; gap: 8px; }
.evidence-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.evidence-pill { padding: 4px 8px; border-radius: 6px; background: #eef4fb; font-size: 12px; }
.evidence-pill.danger { background: #fef0f0; color: #b42318; }
.evidence-pill.warning { background: #fff7e6; color: #8a5b00; }
.parameter-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px; margin-top: 8px; }
.parameter-item { padding: 6px 8px; border-radius: 6px; background: #fff; border: 1px solid #e5eaf1; font-size: 12px; }
.citation-line { padding: 8px 0; border-bottom: 1px dashed #e5eaf1; }
.citation-line:last-child { border-bottom: 0; }
.structured-raw { margin-top: 12px; }
.assistant-meta { margin-top: 10px; }
.assistant-tools-result { margin-top: 18px; display: grid; gap: 12px; }
.tool-result { border: 1px solid #e5eaf1; border-radius: 10px; padding: 14px; background: #fbfcfe; }
.tool-result-head, .pending-head, .assistant-session { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.assistant-json { margin: 0; max-height: 260px; overflow: auto; white-space: pre-wrap; word-break: break-word; font-size: 12px; }
.assistant-pending { margin-top: 18px; display: grid; gap: 12px; }
.assistant-citations { margin-top: 18px; display: grid; gap: 12px; }
.assistant-bottom { margin-top: 18px; }
.assistant-tool-list { display: grid; gap: 10px; }
.assistant-tool-item { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px dashed #e5eaf1; }
.assistant-tool-item:last-child { border-bottom: 0; padding-bottom: 0; }
.assistant-session-list { display: grid; gap: 12px; }
.assistant-session { padding-bottom: 12px; border-bottom: 1px dashed #e5eaf1; }
.assistant-session:last-child { border-bottom: 0; padding-bottom: 0; }
</style>
