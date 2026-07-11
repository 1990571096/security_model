<template>
  <el-dialog
    append-to-body
    :close-on-click-modal="false"
    :before-close="crud.cancelCU"
    :visible="crud.status.cu > 0"
    :title="crud.status.title"
    width="700px"
  >
    <el-form
      ref="form"
      :model="form"
      :rules="rules"
      size="small"
      label-width="120px"
    >
      <el-form-item
        label="任务名称"
        prop="taskName"
      >
        <el-input
          v-model="form.taskName"
          placeholder="请输入任务名称"
          style="width: 520px;"
        />
      </el-form-item>

      <el-form-item label="任务描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="请输入任务描述"
          style="width: 520px;"
        />
      </el-form-item>

      <el-form-item
        label="数据集选择"
        prop="datasetId"
      >
        <el-input
          v-model="selectedDatasetName"
          placeholder="请选择数据集"
          readonly
          style="width: 420px;"
          @click="showDatasetSelector = true"
        >
          <el-button slot="append" icon="el-icon-search" @click="showDatasetSelector = true">选择</el-button>
        </el-input>
      </el-form-item>

      <el-form-item
        label="清洗算法"
        prop="algorithmId"
      >
        <el-select
          v-model="form.algorithmId"
          placeholder="请选择清洗算法"
          style="width: 520px;"
          @change="handleAlgorithmChange"
        >
          <el-option
            v-for="item in algorithmOptions"
            :key="item.algorithmId"
            :label="item.algorithmName"
            :value="item.algorithmId"
          >
            <span style="float: left">{{ item.algorithmName }}</span>
            <span style="float: right; color: #8492a6; font-size: 13px">{{ item.algorithmType }}</span>
          </el-option>
        </el-select>
      </el-form-item>

      <!-- 动态参数配置区域 -->
      <div v-if="currentConfigSchema && form.algorithmId" class="parameter-section">
        <el-divider content-position="left">参数配置</el-divider>

        <!--"快速模板选择"部分-->
        <el-form-item v-if="recommendedTemplate" label="推荐配置">
          <div class="recommended-config-display">
            <span class="config-name">{{ recommendedTemplate.templateName }}</span>
            <el-button
              type="primary"
              size="small"
              style="margin-left: 10px;"
              @click="applyRecommendedConfig"
            >
              应用推荐配置
            </el-button>
            <el-button
              type="text"
              size="small"
              style="margin-left: 5px;"
              @click="showTemplateDetail = true"
            >
              查看详情
            </el-button>
          </div>
          <div v-if="recommendedTemplate.description" class="config-description">
            <i class="el-icon-info" />
            {{ recommendedTemplate.description }}
          </div>
        </el-form-item>

        <!-- 动态参数表单 -->
        <DynamicParameterForm
          ref="dynamicForm"
          :config-schema="currentConfigSchema"
          :default-values="currentDefaultValues"
          :parameter-values="parameterValues"
          @parameter-change="handleParameterChange"
        />
      </div>

      <!-- 算法未选择时的提示 -->
      <div v-else-if="!form.algorithmId" class="algorithm-hint">
        <el-alert
          title="请先选择清洗算法"
          description="选择算法后将显示对应的参数配置选项"
          type="info"
          :closable="false"
          show-icon
        />
      </div>

      <!-- 加载中状态 -->
      <div v-else class="loading-hint">
        <el-alert
          title="正在加载算法配置..."
          type="info"
          :closable="false"
          show-icon
        />
      </div>
    </el-form>

    <div
      slot="footer"
      class="dialog-footer"
    >
      <el-button
        type="text"
        @click="crud.cancelCU"
      >
        取消
      </el-button>
      <el-button
        :loading="crud.status.cu === 2"
        type="primary"
        @click="crud.submitCU"
      >
        确认
      </el-button>
    </div>

    <!-- 数据集选择对话框 -->
    <datasetSelector
      :visible.sync="showDatasetSelector"
      @select="handleDatasetSelect"
    />

    <!-- 模板详情对话框 -->
    <el-dialog
      title="模板配置详情"
      :visible.sync="showTemplateDetail"
      width="600px"
      append-to-body
    >
      <div v-if="currentTemplateDetail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="模板名称">{{ currentTemplateDetail.templateName }}</el-descriptions-item>
          <el-descriptions-item label="描述">{{ currentTemplateDetail.description || '无描述' }}</el-descriptions-item>
          <el-descriptions-item label="参数配置">
            <pre class="template-config">{{ formatJSON(currentTemplateDetail.parameterConfig) }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>
  </el-dialog>
</template>

<script>
import { form } from '@crud/crud'
import CRUD from '@crud/crud'
import datasetSelector from './datasetSelector'
import DynamicParameterForm from './dynamicParameterForm'
import { getAlgorithmsByStage, getAlgorithmConfigSchema } from '@/api/system/algorithm'
import { getDefaultTemplateByAlgorithm } from '@/api/system/hyper'
import { get as getDataset } from '@/api/system/dataset'
// import { findEnabledByAlgorithmId, getParameterSchema } from '@/api/system/hyper'

const defaultForm = {
  taskId: null,
  taskName: '',
  description: '',
  datasetId: null,
  algorithmId: null,
  hyperId: null,
  customParams: ''
}

export default {
  components: { datasetSelector, DynamicParameterForm },
  mixins: [form(defaultForm)],
  data() {
    return {
      showDatasetSelector: false,
      selectedDatasetName: '',
      algorithmOptions: [],

      recommendedTemplate: null,
      showTemplateDetail: false,
      currentTemplateDetail: null,
      // 动态参数相关
      currentConfigSchema: null,
      currentDefaultValues: {},
      parameterValues: {},
      rules: {
        taskName: [
          { required: true, message: '请输入任务名称', trigger: 'blur' }
        ],
        datasetId: [
          { required: true, message: '请选择数据集', trigger: 'change' }
        ],
        algorithmId: [
          { required: true, message: '请选择清洗算法', trigger: 'change' }
        ]
      }
    }
  },
  created() {
    this.loadAlgorithms()
  },
  methods: {
    // 表单重置后的操作
    [CRUD.HOOK.afterToCU]() {
      this.selectedDatasetName = ''
      // this.templateOptions = []
      // this.selectedTemplateId = null
      this.recommendedTemplate = null
      this.currentConfigSchema = null
      this.currentDefaultValues = {}
      this.parameterValues = {}
      this.currentTemplateDetail = null

      // 如果是编辑，加载相关数据
      if (this.crud.status.cu === 2 && this.form.datasetId) {
        this.loadDatasetName(this.form.datasetId)
        if (this.form.algorithmId) {
          this.loadRecommendedTemplate(this.form.algorithmId)
          this.loadAlgorithmConfigSchema(this.form.algorithmId)
          // 如果有自定义参数，解析并填充到动态表单
          if (this.form.customParams) {
            try {
              this.parameterValues = JSON.parse(this.form.customParams)
            } catch (e) {
              console.warn('解析已有参数失败:', e)
            }
          }
        }
      }
    },

    // 提交前的操作 - 将动态表单的参数转换为JSON字符串
    [CRUD.HOOK.beforeSubmit]() {
      // 将动态表单的参数值序列化为JSON字符串
      if (Object.keys(this.parameterValues).length > 0) {
        this.form.customParams = JSON.stringify(this.parameterValues)
      } else {
        this.form.customParams = ''
      }
      return true
    },

    // 数据集选择回调
    handleDatasetSelect(dataset) {
      this.form.datasetId = dataset.datasetId
      this.selectedDatasetName = dataset.datasetName
      this.showDatasetSelector = false
      // 手动触发验证
      this.$nextTick(() => {
        this.$refs.form.validateField('datasetId')
      })
    },

    // 算法改变时加载对应的配置模式和模板
    handleAlgorithmChange(algorithmId) {
      // 重置相关数据
      // this.selectedTemplateId = null
      // this.templateOptions = []
      this.recommendedTemplate = null
      this.currentConfigSchema = null
      this.currentDefaultValues = {}
      this.parameterValues = {}
      this.currentTemplateDetail = null

      if (algorithmId) {
        // 加载算法配置模式
        this.loadAlgorithmConfigSchema(algorithmId)
        // 加载对应的超参数模板
        this.loadRecommendedTemplate(algorithmId)
      }
    },

    // 动态参数变化回调
    handleParameterChange(values) {
      this.parameterValues = { ...values }
    },

    // 加载算法列表
    loadAlgorithms() {
      getAlgorithmsByStage('pre').then(response => {
        this.algorithmOptions = response.data || response
      }).catch(err => {
        console.error('加载算法列表失败:', err)
      })
    },

    // 加载算法配置模式
    loadAlgorithmConfigSchema(algorithmId) {
      getAlgorithmConfigSchema(algorithmId).then(response => {
        const result = response.data || response
        this.currentConfigSchema = JSON.parse(result.configSchema || '{}')

        // 提取默认值
        this.currentDefaultValues = {}
        Object.keys(this.currentConfigSchema).forEach(key => {
          const param = this.currentConfigSchema[key]
          if (param.default !== undefined) {
            this.currentDefaultValues[key] = param.default
          }
        })

        // 如果没有已设置的参数值，使用默认值
        if (Object.keys(this.parameterValues).length === 0) {
          this.parameterValues = { ...this.currentDefaultValues }
        }
      }).catch(err => {
        console.error('加载算法配置模式失败:', err)
        this.$message.error('加载算法配置失败')
      })
    },

    // 新增：加载推荐模板
    loadRecommendedTemplate(algorithmId) {
      getDefaultTemplateByAlgorithm(algorithmId).then(response => {
        this.recommendedTemplate = response.data || response
      }).catch(err => {
        console.warn('加载推荐配置失败:', err)
        this.recommendedTemplate = null
      })
    },

    // 新增：应用推荐配置
    applyRecommendedConfig() {
      if (!this.recommendedTemplate) {
        this.$message.warning('没有可用的推荐配置')
        return
      }

      try {
        const templateParams = JSON.parse(this.recommendedTemplate.parameterConfig)
        this.parameterValues = { ...templateParams }

        // 通知动态表单更新
        this.$nextTick(() => {
          if (this.$refs.dynamicForm) {
            this.$refs.dynamicForm.updateValues(this.parameterValues)
          }
        })

        this.$message.success(`已应用推荐配置: ${this.recommendedTemplate.templateName}`)

        // 设置模板详情用于查看
        this.currentTemplateDetail = this.recommendedTemplate
      } catch (e) {
        this.$message.error('推荐配置格式错误')
        console.error('解析推荐配置失败:', e)
      }
    },
    // 根据数据集ID加载数据集名称
    loadDatasetName(datasetId) {
      if (!datasetId) return
      getDataset(datasetId).then(response => {
        const dataset = response.data || response
        this.selectedDatasetName = dataset.datasetName || `数据集-${datasetId}`
      }).catch(err => {
        console.error('加载数据集名称失败:', err)
        this.selectedDatasetName = `数据集-${datasetId}`
      })
    },
    // 格式化JSON显示
    formatJSON(jsonString) {
      if (!jsonString) return ''
      try {
        const obj = typeof jsonString === 'string' ? JSON.parse(jsonString) : jsonString
        return JSON.stringify(obj, null, 2)
      } catch (e) {
        return jsonString
      }
    }
  }
}
</script>

<style scoped>
.parameter-section {
  margin-top: 10px;
  padding: 15px;
  background-color: #fafbfc;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
}

.algorithm-hint,
.loading-hint {
  margin: 20px 0;
}

.template-config {
  background-color: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
  margin: 0;
}

::v-deep .el-select .el-input {
  width: 130px;
}

::v-deep .el-select-dropdown__item {
  height: auto;
  line-height: 20px;
  padding: 8px 20px;
}

.dialog-footer {
  text-align: right;
}
.recommended-config-display {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  background-color: #f0f9ff;
  border: 1px solid #bfdbfe;
  border-radius: 4px;
}

.config-name {
  color: #1e40af;
  font-weight: 500;
}

.config-description {
  margin-top: 5px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.4;
}

.config-description .el-icon-info {
  margin-right: 4px;
  color: #3b82f6;
}
</style>
