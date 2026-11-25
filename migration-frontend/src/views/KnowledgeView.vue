<template>
  <div class="page" v-loading="loading">
    <section class="hero-band knowledge-hero">
      <div>
        <h2>知识检索与引用回答</h2>
        <p>查询共享知识库、算法说明和自己的任务报告，查看引用片段及其原文位置。</p>
      </div>
      <div class="hero-summary knowledge-summary">
        <span>可检索文档</span>
        <strong>{{ documentTotal ?? '—' }}</strong>
      </div>
    </section>

    <section class="content-grid knowledge-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div>
              <strong>提出问题</strong>
              <span class="table-caption">问题最多 500 字；检索范围仅共享文档与当前账号私有文档</span>
            </div>
            <el-tag type="info" effect="light">ACL 优先</el-tag>
          </div>
        </template>
        <el-input
          v-model="question"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="例如：R2 中 GPU 租约过期后为什么不能直接接管？"
          @keydown.ctrl.enter="ask"
        />
        <div class="knowledge-actions">
          <el-button type="primary" :loading="loading" @click="ask">检索并回答</el-button>
          <el-button text @click="clearResult">清空结果</el-button>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <div><strong>回答与模式</strong><span class="table-caption">answer 只在模型可用且引用校验通过时返回</span></div>
            <el-tag v-if="result" :type="statusType" effect="dark">{{ statusLabel }}</el-tag>
          </div>
        </template>
        <el-empty v-if="!result" description="提交问题后展示回答或检索片段" />
        <template v-else>
          <el-alert
            v-if="result.status === 'retrieval_only'"
            type="warning"
            :closable="false"
            show-icon
            title="当前为检索模式，未生成模型答案"
            :description="result.modelMessage || '外部模型未启用或暂不可用，以下仅展示可引用片段。'"
          />
          <el-alert
            v-else-if="result.status === 'unknown'"
            type="info"
            :closable="false"
            show-icon
            title="知识库中没有足够依据"
            :description="result.modelMessage || '未检索到达到阈值的片段，系统未调用外部模型。'"
          />
          <div v-else class="knowledge-answer">{{ result.answer }}</div>
          <p v-if="result" class="table-caption knowledge-model-line">
            模型状态：{{ result.modelStatus || 'unknown' }} · 引用 {{ result.citations?.length || 0 }} 条
            · {{ result.retrievalMode === 'hybrid' ? `混合检索（${result.vectorHits || 0} 条向量）` : '关键词检索' }}
          </p>
        </template>
      </el-card>
    </section>

    <section v-if="result?.citations?.length" class="knowledge-citations">
      <el-card v-for="citation in result.citations" :key="citation.citationId" shadow="never">
        <div class="citation-head">
          <div>
            <strong>{{ citation.citationId }} · {{ citation.title }}</strong>
            <span class="table-caption">
              v{{ citation.version }} · {{ citation.sourceKey }}
              <template v-if="citation.sectionPath"> · {{ citation.sectionPath }}</template>
            </span>
          </div>
          <div class="citation-tags">
            <el-tag size="small" :type="citation.visibility === 'private' ? 'warning' : 'success'" effect="light">
              {{ citation.visibility === 'private' ? '私有' : '共享' }}
            </el-tag>
            <el-tag size="small" effect="plain">L{{ citation.startLine }}-{{ citation.endLine }}</el-tag>
          </div>
        </div>
        <p class="citation-snippet">{{ citation.snippet }}</p>
      </el-card>
    </section>

    <section class="knowledge-catalog" aria-labelledby="knowledge-catalog-title">
      <el-card shadow="never" v-loading="catalogLoading">
        <template #header>
          <div class="card-heading">
            <div>
              <strong id="knowledge-catalog-title">已索引文档</strong>
              <span class="table-caption">仅列出当前账号可检索的有效版本。</span>
            </div>
            <el-button :disabled="catalogLoading" @click="loadDocuments(documentPage)">刷新列表</el-button>
          </div>
        </template>
        <el-alert v-if="catalogError" type="error" :closable="false" show-icon :title="catalogError" />
        <ul v-else-if="documents.length" class="knowledge-document-list">
          <li v-for="document in documents" :key="`${document.sourceKey}:${document.version}`">
            <div class="knowledge-document-title">
              <strong>{{ document.title }}</strong>
              <span class="table-caption">{{ document.sourceKey }}</span>
            </div>
            <div class="knowledge-document-meta">
              <span>{{ sourceLabels[document.sourceType] || document.sourceType }}</span>
              <el-tag size="small" :type="document.visibility === 'private' ? 'warning' : 'success'">
                {{ document.visibility === 'private' ? '私有' : '共享' }}
              </el-tag>
              <span>版本 {{ document.version }}</span>
              <time v-if="document.updatedAt" :datetime="document.updatedAt">{{ formatDateTime(document.updatedAt) }}</time>
            </div>
          </li>
        </ul>
        <el-empty v-else-if="!catalogLoading" description="当前没有可检索的文档" />
        <el-pagination
          v-if="documentTotal > documentsPerPage"
          class="knowledge-document-pagination"
          background
          layout="prev, pager, next"
          :pager-count="5"
          :current-page="documentPage"
          :page-size="documentsPerPage"
          :total="documentTotal"
          :disabled="catalogLoading"
          @current-change="loadDocuments"
        />
      </el-card>
    </section>

    <section class="content-grid knowledge-admin-grid">
      <el-card shadow="never">
        <template #header>
          <div class="card-heading">
            <strong>知识索引</strong>
          </div>
        </template>
        <div v-if="isAdmin" class="knowledge-actions">
          <el-button :loading="maintaining" @click="runReindex('knowledge')">同步共享知识库</el-button>
          <el-button :loading="maintaining" @click="runReindex('algorithm')">重建算法知识</el-button>
        </div>
        <el-input
          v-model="taskId"
          class="knowledge-task-input"
          placeholder="输入自己的任务 ID，生成私有报告摘要"
          clearable
        >
          <template #append>
            <el-button :loading="maintaining" @click="indexReport">索引报告</el-button>
          </template>
        </el-input>
        <el-alert
          v-if="maintenanceMessage"
          class="knowledge-maintenance"
          :type="maintenanceType"
          :closable="false"
          show-icon
          :title="maintenanceMessage"
        />
      </el-card>
      <el-card shadow="never">
        <template #header><strong>边界说明</strong></template>
        <div class="definition-list knowledge-boundaries">
          <div><span>共享知识库</span><p>管理员可同步独立知识库目录中的 Markdown 文档。</p></div>
          <div><span>算法目录</span><p>管理员可索引已启用算法的名称和说明。</p></div>
          <div><span>任务报告</span><p>输入自己的任务 ID 可索引报告摘要，默认仅本人可检索。原始产物从任务详情查看。</p></div>
          <div><span>不外发</span><p>本机路径、凭据、原始样本、日志、其他用户私有报告不会进入模型请求。</p></div>
          <div><span>检索回答</span><p>知识检索的回答模型独立配置。模型关闭或不可用时，页面显示检索片段和引用。</p></div>
        </div>
      </el-card>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getUserInfo } from '../api/auth'
import {
  indexTaskReport,
  listKnowledgeDocuments,
  queryKnowledge,
  reindexKnowledge,
  reindexKnowledgeAlgorithms
} from '../api/knowledge'
import { formatDateTime } from '../utils/time'

const loading = ref(false)
const maintaining = ref(false)
const isAdmin = ref(false)
const documents = ref([])
const documentTotal = ref(null)
const documentPage = ref(1)
const documentsPerPage = 10
const catalogLoading = ref(false)
const catalogError = ref('')
const sourceLabels = { knowledge: '共享知识库', algorithm: '算法目录', task_report: '任务报告' }
const question = ref('')
const result = ref(null)
const taskId = ref('')
const maintenanceMessage = ref('')
const maintenanceType = ref('info')

const statusLabel = computed(() => ({
  answered: '模型回答',
  retrieval_only: '检索片段',
  unknown: '无依据拒答'
}[result.value?.status] || '未知状态'))

const statusType = computed(() => ({
  answered: 'success',
  retrieval_only: 'warning',
  unknown: 'info'
}[result.value?.status] || 'info'))

async function loadDocuments(page = 1) {
  catalogLoading.value = true
  catalogError.value = ''
  try {
    let response = await listKnowledgeDocuments(page, documentsPerPage)
    if (page > 1 && !response.data.documents.length) {
      page = 1
      response = await listKnowledgeDocuments(page, documentsPerPage)
    }
    documentPage.value = page
    documentTotal.value = response.data.total
    documents.value = response.data.documents
  } catch (error) {
    documents.value = []
    documentTotal.value = null
    catalogError.value = error.response?.data?.message || '文档列表加载失败，请刷新列表。'
  } finally {
    catalogLoading.value = false
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
    const response = await queryKnowledge(value)
    result.value = response.data || null
  } catch (error) {
    result.value = null
    ElMessage.error(error.response?.data?.message || '知识检索失败')
  } finally {
    loading.value = false
  }
}

function clearResult() {
  result.value = null
}

async function runReindex(kind) {
  maintaining.value = true
  maintenanceMessage.value = ''
  try {
    const response = kind === 'knowledge' ? await reindexKnowledge() : await reindexKnowledgeAlgorithms()
    const data = response.data || {}
    maintenanceMessage.value = `扫描 ${data.scanned ?? 0}，更新 ${data.changed ?? 0}，退役 ${data.retired ?? 0}，跳过 ${data.skipped ?? 0}，失败 ${data.failed ?? 0}，新增分块 ${data.chunks ?? 0}`
    maintenanceType.value = Number(data.failed || 0) > 0 ? 'warning' : 'success'
    await loadDocuments()
  } catch (error) {
    maintenanceType.value = error.response?.status === 403 ? 'warning' : 'error'
    maintenanceMessage.value = error.response?.status === 403
      ? '当前账号不是管理员，索引维护被拒绝。'
      : (error.response?.data?.message || '索引维护失败')
  } finally {
    maintaining.value = false
  }
}

async function indexReport() {
  const id = taskId.value.trim()
  if (!/^\d+$/.test(id)) {
    ElMessage.warning('请输入数字任务 ID')
    return
  }
  maintaining.value = true
  maintenanceMessage.value = ''
  try {
    await indexTaskReport(id)
    maintenanceMessage.value = `任务 ${id} 的私有报告摘要已重建。`
    maintenanceType.value = 'success'
    await loadDocuments()
  } catch (error) {
    maintenanceType.value = 'warning'
    maintenanceMessage.value = error.response?.data?.message || '报告索引失败'
  } finally {
    maintaining.value = false
  }
}

onMounted(() => {
  loadDocuments()
  getUserInfo().then(({ data }) => { isAdmin.value = data.admin === true }).catch(() => {})
})
</script>

<style scoped>
.knowledge-grid {
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  margin-top: 18px;
}

.knowledge-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.knowledge-answer {
  white-space: pre-wrap;
  line-height: 1.8;
  color: var(--el-text-color-primary);
}

.knowledge-model-line {
  margin-top: 12px;
}

.knowledge-citations {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
  margin-top: 16px;
}

.citation-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.citation-head strong {
  display: block;
  font-size: 14px;
}

.citation-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.citation-snippet {
  margin: 12px 0 0;
  color: var(--el-text-color-regular);
  line-height: 1.7;
  max-height: 190px;
  overflow: auto;
}

.knowledge-admin-grid {
  margin-top: 16px;
}

.knowledge-catalog {
  margin-top: 16px;
}

.knowledge-document-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.knowledge-document-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px 20px;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.knowledge-document-list li:last-child {
  border-bottom: 0;
}

.knowledge-document-title {
  overflow-wrap: anywhere;
}

.knowledge-document-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.knowledge-document-pagination {
  margin-top: 12px;
}

.knowledge-task-input {
  margin-top: 12px;
  max-width: 520px;
}

.knowledge-maintenance {
  margin-top: 12px;
}

.knowledge-boundaries p {
  line-height: 1.6;
}

@media (max-width: 900px) {
  .knowledge-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .knowledge-document-list li {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
