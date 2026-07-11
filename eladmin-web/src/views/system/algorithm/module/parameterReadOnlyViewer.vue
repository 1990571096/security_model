<template>
  <div class="parameter-readonly-viewer">
    <!-- 当没有参数配置时显示 -->
    <div v-if="!hasParameters" class="empty-parameters">
      <el-alert
        title="暂无参数配置"
        description="该算法尚未配置参数"
        type="info"
        :closable="false"
        show-icon
      />
    </div>

    <!-- 参数配置表格 -->
    <div v-else class="parameters-table">
      <el-table
        :data="parameterList"
        border
        size="small"
        style="width: 100%"
      >
        <el-table-column
          prop="name"
          label="参数名称"
          width="140"
          align="center"
        >
          <template slot-scope="scope">
            <span class="parameter-name">{{ scope.row.name }}</span>
          </template>
        </el-table-column>

        <el-table-column
          prop="type"
          label="参数类型"
          width="90"
          align="center"
        >
          <template slot-scope="scope">
            <el-tag
              :type="getTypeTagType(scope.row.type)"
              size="mini"
            >
              {{ getTypeLabel(scope.row.type) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column
          prop="defaultValue"
          label="默认值"
          width="90"
          align="center"
        >
          <template slot-scope="scope">
           <span class="default-value">
             {{ formatDefaultValue(scope.row.defaultValue, scope.row.type) }}
           </span>
          </template>
        </el-table-column>

        <el-table-column
          prop="range"
          label="取值范围"
          width="150"
          align="center"
        >
          <template slot-scope="scope">
           <span class="parameter-range">
             {{ getParameterRange(scope.row) }}
           </span>
          </template>
        </el-table-column>

        <el-table-column
          prop="required"
          label="是否必需"
          width="80"
          align="center"
        >
          <template slot-scope="scope">
            <el-tag
              :type="scope.row.required ? 'danger' : 'info'"
              size="mini"
            >
              {{ scope.row.required ? '必需' : '可选' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column
          prop="description"
          label="参数说明"
          min-width="150"
        >
          <template slot-scope="scope">
           <span class="parameter-description">
             {{ scope.row.description || '暂无说明' }}
           </span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 原始配置预览 -->
<!--      <div class="raw-config-section" style="margin-top: 20px;">-->
<!--        <el-divider content-position="left">原始配置</el-divider>-->
<!--        <el-tabs v-model="activeTab" size="small">-->
<!--          <el-tab-pane label="配置模式" name="schema">-->
<!--            <div class="json-preview">-->
<!--              <el-button-->
<!--                type="text"-->
<!--                icon="el-icon-copy-document"-->
<!--                size="mini"-->
<!--                style="float: right; margin-bottom: 10px;"-->
<!--                @click="copyToClipboard(formattedConfigSchema)"-->
<!--              >-->
<!--                复制-->
<!--              </el-button>-->
<!--              <pre class="json-content">{{ formattedConfigSchema }}</pre>-->
<!--            </div>-->
<!--          </el-tab-pane>-->
<!--          <el-tab-pane label="默认配置" name="default">-->
<!--            <div class="json-preview">-->
<!--              <el-button-->
<!--                type="text"-->
<!--                icon="el-icon-copy-document"-->
<!--                size="mini"-->
<!--                style="float: right; margin-bottom: 10px;"-->
<!--                @click="copyToClipboard(formattedDefaultConfig)"-->
<!--              >-->
<!--                复制-->
<!--              </el-button>-->
<!--              <pre class="json-content">{{ formattedDefaultConfig }}</pre>-->
<!--            </div>-->
<!--          </el-tab-pane>-->
<!--        </el-tabs>-->
<!--      </div>-->
    </div>
  </div>
</template>

<script>
export default {
  name: 'ParameterReadOnlyViewer',
  props: {
    configSchema: {
      type: String,
      default: ''
    },
    defaultConfig: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      activeTab: 'schema'
    }
  },
  computed: {
    // 解析配置模式
    parsedConfigSchema() {
      try {
        return this.configSchema ? JSON.parse(this.configSchema) : {}
      } catch (e) {
        console.warn('配置模式JSON解析失败:', e)
        return {}
      }
    },

    // 解析默认配置
    parsedDefaultConfig() {
      try {
        return this.defaultConfig ? JSON.parse(this.defaultConfig) : {}
      } catch (e) {
        console.warn('默认配置JSON解析失败:', e)
        return {}
      }
    },

    // 是否有参数配置
    hasParameters() {
      return Object.keys(this.parsedConfigSchema).length > 0
    },

    // 参数列表（用于表格显示）
    parameterList() {
      const params = []
      Object.keys(this.parsedConfigSchema).forEach(paramName => {
        const paramConfig = this.parsedConfigSchema[paramName]
        const defaultValue = this.parsedDefaultConfig[paramName] || paramConfig.default

        params.push({
          name: paramName,
          type: paramConfig.type || 'string',
          defaultValue: defaultValue,
          required: paramConfig.required !== false,
          description: paramConfig.description || '',
          min: paramConfig.min,
          max: paramConfig.max,
          options: paramConfig.options || []
        })
      })
      return params
    },

    // 格式化的配置模式JSON
    formattedConfigSchema() {
      if (!this.configSchema) return '暂无配置'
      try {
        return JSON.stringify(JSON.parse(this.configSchema), null, 2)
      } catch (e) {
        return this.configSchema
      }
    },

    // 格式化的默认配置JSON
    formattedDefaultConfig() {
      if (!this.defaultConfig) return '暂无配置'
      try {
        return JSON.stringify(JSON.parse(this.defaultConfig), null, 2)
      } catch (e) {
        return this.defaultConfig
      }
    }
  },
  methods: {
    // 获取类型标签样式
    getTypeTagType(type) {
      const typeMap = {
        'int': 'primary',
        'float': 'success',
        'boolean': 'warning',
        'select': 'info',
        'string': 'default'
      }
      return typeMap[type] || 'default'
    },

    // 获取类型标签文字
    getTypeLabel(type) {
      const typeMap = {
        'int': '整数',
        'float': '浮点数',
        'boolean': '布尔值',
        'select': '选择项',
        'string': '字符串'
      }
      return typeMap[type] || type
    },

    // 格式化默认值显示
    formatDefaultValue(value, type) {
      if (value === undefined || value === null) {
        return '-'
      }

      if (type === 'boolean') {
        return value ? 'true' : 'false'
      }

      if (type === 'select' || type === 'string') {
        return `"${value}"`
      }

      return String(value)
    },

    // 获取参数取值范围
    getParameterRange(param) {
      if (param.type === 'int' || param.type === 'float') {
        if (param.min !== undefined && param.max !== undefined) {
          return `${param.min} ~ ${param.max}`
        } else if (param.min !== undefined) {
          return `≥ ${param.min}`
        } else if (param.max !== undefined) {
          return `≤ ${param.max}`
        }
        return '无限制'
      }

      if (param.type === 'select' && param.options && param.options.length > 0) {
        return param.options.join(', ')
      }

      if (param.type === 'boolean') {
        return 'true / false'
      }

      return '-'
    },

    // 复制到剪贴板
    copyToClipboard(text) {
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(() => {
          this.$message.success('已复制到剪贴板')
        }).catch(() => {
          this.fallbackCopyToClipboard(text)
        })
      } else {
        this.fallbackCopyToClipboard(text)
      }
    },

    // 兼容性复制方法
    fallbackCopyToClipboard(text) {
      const textArea = document.createElement('textarea')
      textArea.value = text
      textArea.style.position = 'fixed'
      textArea.style.opacity = '0'
      document.body.appendChild(textArea)
      textArea.select()
      try {
        document.execCommand('copy')
        this.$message.success('已复制到剪贴板')
      } catch (err) {
        this.$message.error('复制失败，请手动复制')
      }
      document.body.removeChild(textArea)
    }
  }
}
</script>

<style scoped>
.parameter-readonly-viewer {
  width: 100%;
}

.empty-parameters {
  margin: 20px 0;
}

.parameters-table {
  width: 100%;
}

.parameter-name {
  font-weight: 500;
  color: #303133;
  font-family: 'Courier New', monospace;
}

.default-value {
  font-family: 'Courier New', monospace;
  color: #409EFF;
  font-weight: 500;
}

.parameter-range {
  color: #606266;
  font-size: 12px;
}

.parameter-description {
  color: #606266;
  line-height: 1.4;
}

.raw-config-section {
  border-top: 1px solid #ebeef5;
  padding-top: 15px;
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
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
  color: #2c3e50;
}

/* 表格样式优化 */
::v-deep .el-table {
  border-radius: 4px;
  overflow: hidden;
}

::v-deep .el-table th {
  background-color: #f8f9fa;
  color: #303133;
  font-weight: 500;
}

::v-deep .el-table td {
  border-bottom: 1px solid #ebeef5;
}

::v-deep .el-table__row:hover > td {
  background-color: #f5f7fa;
}

::v-deep .el-tabs__content {
  padding-top: 10px;
}

/* 标签样式 */
::v-deep .el-tag {
  border-radius: 2px;
}
</style>
