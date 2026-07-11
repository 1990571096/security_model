<template>
  <div class="dynamic-parameter-form">
    <el-form
      ref="parameterForm"
      :model="formValues"
      :rules="formRules"
      size="small"
      label-width="150px"
    >
      <div v-if="!hasParameters" class="no-parameters">
        <el-alert
          title="该算法无需配置参数"
          type="info"
          :closable="false"
          show-icon
        />
      </div>

      <el-form-item
        v-for="(paramConfig, paramName) in configSchema"
        :key="paramName"
        :label="getParameterLabel(paramName, paramConfig)"
        :prop="paramName"
        class="parameter-item"
      >
        <!-- 数值类型参数 (int/float) -->
        <el-input-number
          v-if="paramConfig.type === 'int' || paramConfig.type === 'float'"
          v-model="formValues[paramName]"
          :min="paramConfig.min"
          :max="paramConfig.max"
          :precision="paramConfig.type === 'float' ? 4 : 0"
          :step="getStep(paramConfig)"
          :placeholder="getPlaceholder(paramConfig)"
          style="width: 200px;"
          @change="handleValueChange(paramName, $event)"
        />

        <!-- 选择类型参数 (select) -->
        <el-select
          v-else-if="paramConfig.type === 'select'"
          v-model="formValues[paramName]"
          :placeholder="getPlaceholder(paramConfig)"
          style="width: 200px;"
          @change="handleValueChange(paramName, $event)"
        >
          <el-option
            v-for="option in paramConfig.options"
            :key="option"
            :label="option"
            :value="option"
          />
        </el-select>

        <!-- 布尔类型参数 (boolean) -->
        <el-switch
          v-else-if="paramConfig.type === 'boolean'"
          v-model="formValues[paramName]"
          :active-text="'启用'"
          :inactive-text="'禁用'"
          @change="handleValueChange(paramName, $event)"
        />

        <!-- 未知类型参数 (文本输入) -->
        <el-input
          v-else
          v-model="formValues[paramName]"
          :placeholder="getPlaceholder(paramConfig)"
          style="width: 200px;"
          @change="handleValueChange(paramName, $event)"
        />

        <!-- 参数说明 -->
        <div v-if="paramConfig.description" class="parameter-description">
          <i class="el-icon-info"></i>
          {{ paramConfig.description }}
        </div>

        <!-- 参数范围提示 -->
        <div v-if="getRangeText(paramConfig)" class="parameter-range">
          <i class="el-icon-warning-outline"></i>
          {{ getRangeText(paramConfig) }}
        </div>
      </el-form-item>
    </el-form>

    <!-- 参数预览区域 -->
    <div v-if="hasParameters && showPreview" class="parameter-preview">
      <el-divider content-position="left">参数预览</el-divider>
      <el-card class="preview-card">
        <div slot="header" class="clearfix">
          <span>当前参数配置</span>
          <el-button
            style="float: right; padding: 3px 0"
            type="text"
            icon="el-icon-copy-document"
            @click="copyParameters"
          >
            复制
          </el-button>
        </div>
        <pre class="parameter-json">{{ formattedParameters }}</pre>
      </el-card>
    </div>
  </div>
</template>

<script>
export default {
  name: 'DynamicParameterForm',
  props: {
    // 参数配置模式
    configSchema: {
      type: Object,
      default: () => ({})
    },
    // 默认值
    defaultValues: {
      type: Object,
      default: () => ({})
    },
    // 当前参数值
    parameterValues: {
      type: Object,
      default: () => ({})
    },
    // 是否显示参数预览
    showPreview: {
      type: Boolean,
      default: true
    }
  },
  data() {
    return {
      formValues: {},
      formRules: {}
    }
  },
  computed: {
    // 是否有参数需要配置
    hasParameters() {
      return Object.keys(this.configSchema).length > 0
    },
    // 格式化的参数JSON
    formattedParameters() {
      const validValues = {}
      Object.keys(this.formValues).forEach(key => {
        if (this.formValues[key] !== null && this.formValues[key] !== undefined && this.formValues[key] !== '') {
          validValues[key] = this.formValues[key]
        }
      })
      return JSON.stringify(validValues, null, 2)
    }
  },
  watch: {
    // 监听配置模式变化
    configSchema: {
      handler(newSchema) {
        this.initializeForm(newSchema)
      },
      immediate: true,
      deep: true
    },
    // 监听外部参数值变化
    parameterValues: {
      handler(newValues) {
        this.formValues = { ...this.formValues, ...newValues }
      },
      deep: true
    }
  },
  methods: {
    // 初始化表单
    initializeForm(schema) {
      this.formValues = {}
      this.formRules = {}

      Object.keys(schema).forEach(paramName => {
        const paramConfig = schema[paramName]

        // 设置初始值（优先级：传入值 > 默认值 > 类型默认值）
        let initialValue = this.parameterValues[paramName]
        if (initialValue === undefined) {
          initialValue = this.defaultValues[paramName]
        }
        if (initialValue === undefined) {
          initialValue = this.getTypeDefaultValue(paramConfig)
        }

        this.$set(this.formValues, paramName, initialValue)

        // 设置验证规则
        this.$set(this.formRules, paramName, this.getValidationRules(paramConfig))
      })

      // 初始化完成后触发一次变化事件
      this.$nextTick(() => {
        this.emitParameterChange()
      })
    },

    // 获取类型默认值
    getTypeDefaultValue(paramConfig) {
      if (paramConfig.default !== undefined) {
        return paramConfig.default
      }

      switch (paramConfig.type) {
        case 'int':
          return paramConfig.min || 0
        case 'float':
          return paramConfig.min || 0.0
        case 'boolean':
          return false
        case 'select':
          return paramConfig.options && paramConfig.options.length > 0 ? paramConfig.options[0] : ''
        default:
          return ''
      }
    },

    // 获取验证规则
    getValidationRules(paramConfig) {
      const rules = []

      // 必填验证
      if (paramConfig.required !== false) {
        rules.push({
          required: true,
          message: `请设置${paramConfig.description || '参数'}`,
          trigger: 'change'
        })
      }

      // 数值范围验证
      if (paramConfig.type === 'int' || paramConfig.type === 'float') {
        if (paramConfig.min !== undefined) {
          rules.push({
            type: 'number',
            min: paramConfig.min,
            message: `值不能小于${paramConfig.min}`,
            trigger: 'change'
          })
        }
        if (paramConfig.max !== undefined) {
          rules.push({
            type: 'number',
            max: paramConfig.max,
            message: `值不能大于${paramConfig.max}`,
            trigger: 'change'
          })
        }
      }

      return rules
    },

    // 获取参数标签
    getParameterLabel(paramName, paramConfig) {
      return paramConfig.description || paramName
    },

    // 获取占位符文本
    getPlaceholder(paramConfig) {
      if (paramConfig.default !== undefined) {
        return `默认值: ${paramConfig.default}`
      }
      return `请输入${paramConfig.description || '参数值'}`
    },

    // 获取数值步长
    getStep(paramConfig) {
      if (paramConfig.type === 'float') {
        return 0.001
      }
      return 1
    },

    // 获取范围提示文本
    getRangeText(paramConfig) {
      if (paramConfig.type === 'int' || paramConfig.type === 'float') {
        if (paramConfig.min !== undefined && paramConfig.max !== undefined) {
          return `范围: ${paramConfig.min} - ${paramConfig.max}`
        } else if (paramConfig.min !== undefined) {
          return `最小值: ${paramConfig.min}`
        } else if (paramConfig.max !== undefined) {
          return `最大值: ${paramConfig.max}`
        }
      }
      return ''
    },

    // 参数值变化处理
    handleValueChange(paramName, value) {
      this.$set(this.formValues, paramName, value)
      this.emitParameterChange()
    },

    // 触发参数变化事件
    emitParameterChange() {
      // 过滤掉无效值
      const validValues = {}
      Object.keys(this.formValues).forEach(key => {
        const value = this.formValues[key]
        if (value !== null && value !== undefined && value !== '') {
          validValues[key] = value
        }
      })

      this.$emit('parameter-change', validValues)
    },

    // 外部调用：更新参数值
    updateValues(newValues) {
      Object.keys(newValues).forEach(key => {
        if (this.formValues.hasOwnProperty(key)) {
          this.$set(this.formValues, key, newValues[key])
        }
      })
      this.emitParameterChange()
    },

    // 验证表单
    validateForm() {
      return new Promise((resolve, reject) => {
        this.$refs.parameterForm.validate((valid) => {
          if (valid) {
            resolve(this.formValues)
          } else {
            reject(new Error('参数验证失败'))
          }
        })
      })
    },

    // 重置表单
    resetForm() {
      this.$refs.parameterForm.resetFields()
      this.initializeForm(this.configSchema)
    },

    // 复制参数到剪贴板
    copyParameters() {
      const text = this.formattedParameters
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(() => {
          this.$message.success('参数配置已复制到剪贴板')
        }).catch(() => {
          this.fallbackCopyText(text)
        })
      } else {
        this.fallbackCopyText(text)
      }
    },

    // 降级复制方法
    fallbackCopyText(text) {
      const textArea = document.createElement('textarea')
      textArea.value = text
      document.body.appendChild(textArea)
      textArea.select()
      try {
        document.execCommand('copy')
        this.$message.success('参数配置已复制到剪贴板')
      } catch (err) {
        this.$message.error('复制失败，请手动复制')
      }
      document.body.removeChild(textArea)
    }
  }
}
</script>

<style scoped>
.dynamic-parameter-form {
  width: 100%;
}

.no-parameters {
  text-align: center;
  padding: 20px;
}

.parameter-item {
  margin-bottom: 15px;
}

.parameter-description {
  margin-top: 5px;
  font-size: 12px;
  color: #606266;
  line-height: 1.4;
}

.parameter-description .el-icon-info {
  margin-right: 4px;
  color: #409EFF;
}

.parameter-range {
  margin-top: 3px;
  font-size: 11px;
  color: #909399;
}

.parameter-range .el-icon-warning-outline {
  margin-right: 4px;
  color: #E6A23C;
}

.parameter-preview {
  margin-top: 20px;
}

.preview-card {
  margin-top: 10px;
}

.parameter-json {
  background-color: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
  margin: 0;
  font-family: 'Courier New', monospace;
}

/* 表单控件样式优化 */
::v-deep .el-form-item__label {
  font-weight: 500;
  color: #303133;
}

::v-deep .el-input-number .el-input__inner {
  text-align: left;
}

::v-deep .el-switch__label {
  font-size: 12px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .parameter-item {
    margin-bottom: 20px;
  }

  ::v-deep .el-form-item__label {
    width: 100px !important;
  }
}
</style>
