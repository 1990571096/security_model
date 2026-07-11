<template>
  <el-dialog
    :visible="visible"
    title="选择模型"
    width="900px"
    :before-close="handleClose"
    append-to-body
  >
    <!-- 搜索工具栏 -->
    <div class="search-container">
      <el-row :gutter="10">
        <el-col :span="6">
          <el-input
            v-model="searchForm.blurry"
            placeholder="搜索模型名称"
            size="small"
            clearable
            @keyup.enter.native="loadModels"
          >
            <i slot="prefix" class="el-input__icon el-icon-search"></i>
          </el-input>
        </el-col>
        <el-col :span="4">
          <el-select
            v-model="searchForm.modelType"
            placeholder="模型类型"
            size="small"
            clearable
            @change="loadModels"
          >
            <el-option
              v-for="item in modelTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select
            v-model="searchForm.status"
            placeholder="模型状态"
            size="small"
            clearable
            @change="loadModels"
          >
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-col>
        <el-col :span="3">
          <el-button
            type="primary"
            size="small"
            icon="el-icon-search"
            @click="loadModels"
          >
            搜索
          </el-button>
        </el-col>
        <el-col :span="3">
          <el-button
            type="success"
            size="small"
            icon="el-icon-upload"
            @click="showUploadDialog = true"
          >
            上传模型
          </el-button>
        </el-col>
        <el-col :span="4">
          <el-button
            type="info"
            size="small"
            icon="el-icon-refresh"
            @click="refreshModels"
          >
            刷新
          </el-button>
        </el-col>
      </el-row>
    </div>

    <!-- 模型列表表格 -->
    <el-table
      ref="modelTable"
      v-loading="loading"
      :data="modelList"
      style="width: 100%; margin-top: 15px;"
      highlight-current-row
      @current-change="handleCurrentChange"
      @row-dblclick="handleRowDoubleClick"
      height="400px"
    >
      <el-table-column type="index" width="50" label="#" />
      <el-table-column prop="modelName" label="模型名称" min-width="150">
        <template slot-scope="scope">
          <el-tooltip :content="scope.row.description || '无描述'" placement="top">
            <span>{{ scope.row.modelName }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="modelType" label="类型" width="120">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getTypeTagType(scope.row.modelType)">
            {{ getTypeLabel(scope.row.modelType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="模型状态" width="100">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getStatusTagType(scope.row.status)">
            {{ getStatusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="modelSize" label="模型大小" width="100">
        <template slot-scope="scope">
          {{ formatFileSize(scope.row.modelSize) }}
        </template>
      </el-table-column>
      <el-table-column prop="accuracy" label="准确率" width="80">
        <template slot-scope="scope">
          <span v-if="scope.row.accuracy">{{ (scope.row.accuracy * 100).toFixed(1) }}%</span>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="framework" label="框架" width="100">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getFrameworkTagType(scope.row.framework)">
            {{ scope.row.framework || '未知' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="150" />
      <el-table-column label="可用性" width="80" align="center">
        <template slot-scope="scope">
          <el-tag
            size="mini"
            :type="isModelAvailable(scope.row) ? 'success' : 'danger'"
          >
            {{ isModelAvailable(scope.row) ? '可用' : '不可用' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination-container">
      <el-pagination
        v-show="total > 0"
        :current-page="searchForm.page"
        :page-sizes="[10, 20, 50, 100]"
        :page-size="searchForm.size"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 选中模型信息 -->
    <div v-if="selectedModel" class="selected-info">
      <el-alert
        :title="`已选择模型: ${selectedModel.modelName}`"
        type="info"
        :closable="false"
        show-icon
      >
        <template slot="default">
          <p><strong>模型类型:</strong> {{ getTypeLabel(selectedModel.modelType) }}</p>
          <p><strong>模型状态:</strong> {{ getStatusLabel(selectedModel.status) }}</p>
          <p><strong>模型框架:</strong> {{ selectedModel.framework || '未知' }}</p>
          <p v-if="selectedModel.accuracy"><strong>准确率:</strong> {{ (selectedModel.accuracy * 100).toFixed(1) }}%</p>
          <p v-if="selectedModel.description"><strong>描述:</strong> {{ selectedModel.description }}</p>
        </template>
      </el-alert>
    </div>

    <!-- 对话框底部按钮 -->
    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">取消</el-button>
      <el-button
        type="primary"
        :disabled="!selectedModel || !isModelAvailable(selectedModel)"
        @click="handleConfirm"
      >
        确认选择
      </el-button>
    </div>

    <!-- 上传模型对话框 -->
    <uploadForm
      :visible.sync="showUploadDialog"
      @success="handleUploadSuccess"
    />
  </el-dialog>
</template>

<script>
import { getAllModelUnrestricted } from '@/api/system/model'
import uploadForm from '../../model/module/form'

export default {
  name: 'ModelSelector',
  components: { uploadForm },
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    // 模型过滤条件（可选）
    filterType: {
      type: String,
      default: '' // 如果传入，只显示特定类型的模型
    }
  },
  data() {
    return {
      loading: false,
      modelList: [],
      selectedModel: null,
      showUploadDialog: false,
      total: 0,
      searchForm: {
        page: 1,
        size: 10,
        blurry: '',
        modelType: '',
        status: 'available', // 默认只显示可用模型
        enabled: true
      },
      modelTypeOptions: [
        { value: 'CNN', label: 'CNN模型' },
        { value: 'ResNet', label: 'ResNet' },
        { value: 'VGG', label: 'VGG' },
        { value: 'MobileNet', label: 'MobileNet' },
        { value: 'Transformer', label: 'Transformer' },
        { value: 'BERT', label: 'BERT' },
        { value: 'GPT', label: 'GPT' }
      ],
      statusOptions: [
        { value: 'available', label: '可用' },
        { value: 'training', label: '训练中' },
        { value: 'inference', label: '推理中' },
        { value: 'error', label: '错误' },
        { value: 'uploading', label: '上传中' }
      ]
    }
  },
  watch: {
    visible(val) {
      if (val) {
        this.resetSearch()
        // 如果有过滤条件，应用过滤条件
        if (this.filterType) {
          this.searchForm.modelType = this.filterType
        }
        this.loadModels()
      } else {
        this.selectedModel = null
      }
    }
  },
  methods: {
    // 加载模型列表
    loadModels() {
      this.loading = true

      // 构建查询参数
      const params = {
        page: this.searchForm.page - 1, // 后端从0开始
        size: this.searchForm.size,
        enabled: this.searchForm.enabled
      }

      if (this.searchForm.blurry) {
        params.blurry = this.searchForm.blurry
      }
      if (this.searchForm.modelType) {
        params.modelType = this.searchForm.modelType
      }
      if (this.searchForm.status) {
        params.status = this.searchForm.status
      }

      getAllModelUnrestricted().then(response => {
        const result = response.data || response
        this.modelList = result.content || result
        this.total = result.totalElements || this.modelList.length

        // 过滤只显示可用的模型
        if (Array.isArray(this.modelList)) {
          this.modelList = this.modelList.filter(model => this.isModelAvailable(model))
        }
      }).catch(err => {
        console.error('加载模型列表失败:', err)
        this.$message.error('加载模型列表失败')
      }).finally(() => {
        this.loading = false
      })
    },
    // 刷新模型列表
    refreshModels() {
      this.resetSearch()
      this.loadModels()
    },
    // 重置搜索条件
    resetSearch() {
      this.searchForm = {
        page: 1,
        size: 10,
        blurry: '',
        modelType: this.filterType || '',
        status: 'available',
        enabled: true
      }
      this.selectedModel = null
    },
    // 表格当前行改变
    handleCurrentChange(val) {
      this.selectedModel = val
    },
    // 双击行选择
    handleRowDoubleClick(row) {
      if (this.isModelAvailable(row)) {
        this.selectedModel = row
        this.handleConfirm()
      } else {
        this.$message.warning('该模型不可用，请选择其他模型')
      }
    },
    // 分页大小改变
    handleSizeChange(val) {
      this.searchForm.size = val
      this.searchForm.page = 1
      this.loadModels()
    },
    // 当前页改变
    handlePageChange(val) {
      this.searchForm.page = val
      this.loadModels()
    },
    // 上传成功回调
    handleUploadSuccess(model) {
      this.showUploadDialog = false
      this.$message.success('模型上传成功!')
      this.loadModels()
    },
    // 确认选择
    handleConfirm() {
      if (!this.selectedModel) {
        this.$message.warning('请先选择一个模型')
        return
      }

      if (!this.isModelAvailable(this.selectedModel)) {
        this.$message.warning('所选模型不可用，请选择状态为可用的模型')
        return
      }

      this.$emit('select', this.selectedModel)
    },
    // 关闭对话框
    handleClose() {
      this.$emit('update:visible', false)
    },
    // 判断模型是否可用
    isModelAvailable(model) {
      return model && model.enabled && (model.status === 'available' || model.status === 'inference')
    },
    // 格式化文件大小
    formatFileSize(bytes) {
      if (!bytes || bytes === 0) return '未知'
      const sizes = ['Bytes', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(1024))
      return Math.round(bytes / Math.pow(1024, i) * 100) / 100 + ' ' + sizes[i]
    },
    // 获取类型标签样式
    getTypeTagType(type) {
      const typeMap = {
        'CNN': 'primary',
        'ResNet': 'success',
        'VGG': 'warning',
        'MobileNet': 'info',
        'Transformer': 'danger',
        'BERT': 'success',
        'GPT': 'primary'
      }
      return typeMap[type] || 'info'
    },
    // 获取类型标签文字
    getTypeLabel(type) {
      const typeMap = {
        'CNN': 'CNN',
        'ResNet': 'ResNet',
        'VGG': 'VGG',
        'MobileNet': 'MobileNet',
        'Transformer': 'Transformer',
        'BERT': 'BERT',
        'GPT': 'GPT'
      }
      return typeMap[type] || type
    },
    // 获取状态标签样式
    getStatusTagType(status) {
      const statusMap = {
        'available': 'success',
        'training': 'warning',
        'inference': 'primary',
        'error': 'danger',
        'uploading': 'info'
      }
      return statusMap[status] || 'info'
    },
    // 获取状态标签文字
    getStatusLabel(status) {
      const statusMap = {
        'available': '可用',
        'training': '训练中',
        'inference': '推理中',
        'error': '错误',
        'uploading': '上传中'
      }
      return statusMap[status] || status
    },
    // 获取框架标签样式
    getFrameworkTagType(framework) {
      const frameworkMap = {
        'PyTorch': 'success',
        'TensorFlow': 'primary',
        'Keras': 'warning',
        'ONNX': 'info'
      }
      return frameworkMap[framework] || 'info'
    }
  }
}
</script>

<style scoped>
.search-container {
  padding: 10px 0;
  border-bottom: 1px solid #ebeef5;
}

.pagination-container {
  margin-top: 15px;
  text-align: right;
}

.selected-info {
  margin-top: 15px;
}

.selected-info p {
  margin: 5px 0;
  font-size: 13px;
}

.text-muted {
  color: #909399;
  font-size: 12px;
}

::v-deep .el-table__body-wrapper {
  max-height: 400px;
  overflow-y: auto;
}

::v-deep .el-table .current-row {
  background-color: #ecf5ff;
}

::v-deep .el-table .current-row td {
  background-color: #ecf5ff !important;
}

.dialog-footer {
  text-align: right;
}

.dialog-footer .el-button {
  margin-left: 10px;
}
</style>
