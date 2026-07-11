<template>
  <div class="parameter-config-editor">
    <div class="editor-header">
      <h4>参数配置编辑器</h4>
      <el-button
        type="primary"
        size="small"
        icon="el-icon-plus"
        @click="addParameter"
      >
        添加参数
      </el-button>
    </div>

    <!-- 参数列表 -->
    <div v-if="parameters.length === 0" class="empty-parameters">
      <el-alert
        title="暂无参数配置"
        description="点击添加参数按钮开始配置算法参数"
        type="info"
        :closable="false"
        show-icon
      />
    </div>

    <div v-for="(param, index) in parameters" :key="param.id" class="parameter-item">
      <el-card class="parameter-card">
        <div slot="header" class="parameter-header">
          <span>参数 {{ index + 1 }}: {{ param.name || '未命名' }}</span>
          <el-button
            type="text"
            size="small"
            icon="el-icon-delete"
            @click="removeParameter(index)"
            style="color: #F56C6C;"
          >
            删除
          </el-button>
        </div>

        <el-form :model="param" size="small" label-width="80px">
          <el-row :gutter="20">
            <el-col :span="8">
              <el-form-item label="参数名" required>
                <el-input
                  v-model="param.name"
                  placeholder="参数名称"
                  @blur="validateParameterName(param, index)"
                />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="参数类型" required>
                <el-select
                  v-model="param.type"
                  placeholder="选择类型"
                  @change="handleTypeChange(param)"
                  style="width: 100%;"
                >
                  <el-option
                    v-for="type in parameterTypes"
                    :key="type.value"
                    :label="type.label"
                    :value="type.value"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="是否必需">
                <el-switch
                  v-model="param.required"
                  active-text="必需"
                  inactive-text="可选"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="参数描述">
                <el-input
                  v-model="param.description"
                  placeholder="参数说明"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 数值类型参数配置 -->
          <div v-if="param.type === 'int' || param.type === 'float'">
            <el-row :gutter="20">
              <el-col :span="8">
                <el-form-item label="默认值">
                  <el-input-number
                    v-model="param.default"
                    :precision="param.type === 'float' ? 4 : 0"
                    :step="param.type === 'float' ? 0.1 : 1"
                    style="width: 100%;"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="最小值">
                  <el-input-number
                    v-model="param.min"
                    :precision="param.type === 'float' ? 4 : 0"
                    :step="param.type === 'float' ? 0.1 : 1"
                    style="width: 100%;"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="最大值">
                  <el-input-number
                    v-model="param.max"
                    :precision="param.type === 'float' ? 4 : 0"
                    :step="param.type === 'float' ? 0.1 : 1"
                    style="width: 100%;"
                  />
                </el-form-item>
              </el-col>
            </el-row>
          </div>

          <!-- 选择类型参数配置 -->
          <div v-if="param.type === 'select'">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-form-item label="选项配置">
                  <el-tag
                    v-for="option in param.options"
                    :key="option"
                    closable
                    @close="removeOption(param, option)"
                    style="margin-right: 8px; margin-bottom: 8px;"
                  >
                    {{ option }}
                  </el-tag>
                  <el-input
                    v-if="param.inputVisible"
                    ref="saveTagInput"
                    v-model="param.inputValue"
                    size="small"
                    style="width: 100px;"
                    @keyup.enter.native="handleOptionConfirm(param)"
                    @blur="handleOptionConfirm(param)"
                  />
                  <el-button
                    v-else
                    size="small"
                    type="text"
                    @click="showOptionInput(param)"
                  >
                    + 添加选项
                  </el-button>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="默认选项">
                  <el-select
                    v-model="param.default"
                    placeholder="选择默认选项"
                    style="width: 100%;"
                  >
                    <el-option
                      v-for="option in param.options"
                      :key="option"
                      :label="option"
                      :value="option"
                    />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </div>

          <!-- 布尔类型参数配置 -->
          <div v-if="param.type === 'boolean'">
            <el-row :gutter="20">
              <el-col :span="12">
                <el-form-item label="默认值">
                  <el-switch
                    v-model="param.default"
                    active-text="true"
                    inactive-text="false"
                  />
                </el-form-item>
              </el-col>
            </el-row>
          </div>

          <!-- 文本类型参数配置 -->
          <div v-if="param.type === 'string'">
            <el-row :gutter="20">
              <el-col :span="24">
                <el-form-item label="默认值">
                  <el-input
                    v-model="param.default"
                    placeholder="默认文本值"
                  />
                </el-form-item>
              </el-col>
            </el-row>
          </div>
        </el-form>
      </el-card>
    </div>

    <!-- 预览区域 -->
    <div v-if="parameters.length > 0" class="preview-section">
      <el-divider content-position="left">配置预览</el-divider>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="配置模式 (configSchema)" name="schema">
          <div class="json-preview">
            <el-button
              type="text"
              icon="el-icon-copy-document"
              style="float: right; margin-bottom: 10px;"
              @click="copyToClipboard(formattedConfigSchema)"
            >
              复制
            </el-button>
            <pre class="json-content">{{ formattedConfigSchema }}</pre>
          </div>
        </el-tab-pane>
        <el-tab-pane label="默认配置 (defaultConfig)" name="default">
          <div class="json-preview">
            <el-button
              type="text"
              icon="el-icon-copy-document"
              style="float: right; margin-bottom: 10px;"
              @click="copyToClipboard(formattedDefaultConfig)"
            >
              复制
            </el-button>
            <pre class="json-content">{{ formattedDefaultConfig }}</pre>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ParameterConfigEditor',
  props: {
    // 是否自动触发变更事件
    autoEmit: {
      type: Boolean,
      default: true
    },
    // 初始配置模式
    configSchema: {
      type: String,
      default: ''
    },
    // 初始默认配置
    defaultConfig: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      parameters: [],
      activeTab: 'schema',
      parameterTypes: [
        { value: 'int', label: '整数 (int)' },
        { value: 'float', label: '浮点数 (float)' },
        { value: 'boolean', label: '布尔值 (boolean)' },
        { value: 'select', label: '选择项 (select)' },
        { value: 'string', label: '字符串 (string)' }
      ]
    }
  },
  computed: {
    // 生成配置模式JSON
    generatedConfigSchema() {
      const schema = {}
      this.parameters.forEach(param => {
        if (param.name) {
          const paramConfig = {
            type: param.type,
            description: param.description || undefined
          }

          if (param.required !== undefined) {
            paramConfig.required = param.required
          }

          if (param.default !== undefined && param.default !== '') {
            paramConfig.default = param.default
          }

          if (param.type === 'int' || param.type === 'float') {
            if (param.min !== undefined) paramConfig.min = param.min
            if (param.max !== undefined) paramConfig.max = param.max
          }

          if (param.type === 'select' && param.options && param.options.length > 0) {
            paramConfig.options = param.options
          }

          schema[param.name] = paramConfig
        }
      })
      return schema
    },

    // 生成默认配置JSON
    generatedDefaultConfig() {
      const config = {}
      this.parameters.forEach(param => {
        if (param.name && param.default !== undefined && param.default !== '') {
          config[param.name] = param.default
        }
      })
      return config
    },

    // 格式化显示的配置模式
    formattedConfigSchema() {
      return JSON.stringify(this.generatedConfigSchema, null, 2)
    },

    // 格式化显示的默认配置
    formattedDefaultConfig() {
      return JSON.stringify(this.generatedDefaultConfig, null, 2)
    }
  },
  watch: {
    generatedConfigSchema: {
      handler(newVal) {
        this.$emit('config-change', {
          configSchema: JSON.stringify(newVal),
          defaultConfig: JSON.stringify(this.generatedDefaultConfig)
        })
      },
      deep: true
    },
    generatedDefaultConfig: {
      handler(newVal) {
        this.$emit('config-change', {
          configSchema: JSON.stringify(this.generatedConfigSchema),
          defaultConfig: JSON.stringify(newVal)
        })
      },
      deep: true
    }
  },
  mounted() {
    this.initializeFromProps()
  },
  methods: {
    // 从props初始化参数
    initializeFromProps() {
      try {
        if (this.configSchema) {
          const schema = JSON.parse(this.configSchema)
          const defaultVals = this.defaultConfig ? JSON.parse(this.defaultConfig) : {}

          Object.keys(schema).forEach(paramName => {
            const paramConfig = schema[paramName]
            const param = {
              id: Date.now() + Math.random(),
              name: paramName,
              type: paramConfig.type,
              description: paramConfig.description || '',
              required: paramConfig.required !== false,
              default: defaultVals[paramName] || paramConfig.default,
              inputVisible: false,
              inputValue: ''
            }

            if (paramConfig.min !== undefined) param.min = paramConfig.min
            if (paramConfig.max !== undefined) param.max = paramConfig.max
            if (paramConfig.options) param.options = [...paramConfig.options]

            this.parameters.push(param)
          })
        }
      } catch (e) {
        console.warn('初始化参数配置失败:', e)
      }
    },

    // 添加参数
    addParameter() {
      const newParam = {
        id: Date.now() + Math.random(),
        name: '',
        type: 'float',
        description: '',
        required: true,
        default: undefined,
        options: [],
        inputVisible: false,
        inputValue: ''
      }
      this.parameters.push(newParam)
    },

    // 删除参数
    removeParameter(index) {
      this.$confirm('确定要删除该参数吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        this.parameters.splice(index, 1)
      })
    },

    // 参数类型改变
    handleTypeChange(param) {
      // 重置类型相关的配置
      param.default = undefined
      param.min = undefined
      param.max = undefined
      param.options = []

      // 根据类型设置默认值
      if (param.type === 'boolean') {
        param.default = false
      } else if (param.type === 'int') {
        param.default = 0
      } else if (param.type === 'float') {
        param.default = 0.0
      } else if (param.type === 'string') {
        param.default = ''
      }
    },

    // 验证参数名
    validateParameterName(param, index) {
      if (!param.name) return

      const duplicateIndex = this.parameters.findIndex((p, i) =>
        i !== index && p.name === param.name
      )

      if (duplicateIndex !== -1) {
        this.$message.warning('参数名不能重复')
        param.name = ''
      }
    },

    // 显示选项输入框
    showOptionInput(param) {
      param.inputVisible = true
      this.$nextTick(() => {
        this.$refs.saveTagInput && this.$refs.saveTagInput[0].focus()
      })
    },

    // 确认添加选项
    handleOptionConfirm(param) {
      const inputValue = param.inputValue
      if (inputValue && !param.options.includes(inputValue)) {
        param.options.push(inputValue)
      }
      param.inputVisible = false
      param.inputValue = ''
    },

    // 删除选项
    removeOption(param, option) {
      const index = param.options.indexOf(option)
      if (index !== -1) {
        param.options.splice(index, 1)
      }
      // 如果删除的是默认选项，清空默认值
      if (param.default === option) {
        param.default = undefined
      }
    },

    // 复制到剪贴板
    copyToClipboard(text) {
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(() => {
          this.$message.success('已复制到剪贴板')
        })
      } else {
        const textArea = document.createElement('textarea')
        textArea.value = text
        document.body.appendChild(textArea)
        textArea.select()
        try {
          document.execCommand('copy')
          this.$message.success('已复制到剪贴板')
        } catch (err) {
          this.$message.error('复制失败')
        }
        document.body.removeChild(textArea)
      }
    }
  }
}
</script>

<style scoped>
.parameter-config-editor {
  width: 100%;
  max-width: 900px;
  margin: 0 auto;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 10px;
  border-bottom: 1px solid #ebeef5;
}

.editor-header h4 {
  margin: 0;
  color: #303133;
}

.empty-parameters {
  margin: 20px 0;
}

.parameter-item {
  margin-bottom: 20px;
}

.parameter-card {
  border: 1px solid #e4e7ed;
}

.parameter-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.preview-section {
  margin-top: 30px;
}

.json-preview {
  position: relative;
  background-color: #fafbfc;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}

.json-content {
  background-color: #f5f7fa;
  padding: 15px;
  margin: 0;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.4;
  max-height: 300px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
}
::v-deep .el-card__header {
  background-color: #f8f9fa;
  border-bottom: 1px solid #ebeef5;
}

::v-deep .el-tabs__content {
  padding-top: 10px;
}

::v-deep .el-form-item {
  margin-bottom: 15px;
}

::v-deep .el-form-item__label {
  font-weight: 500;
}
</style>
