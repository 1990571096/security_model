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
            v-model="searchForm.modelSource"
            placeholder="模型来源"
            size="small"
            clearable
            @change="loadModels"
          >
            <el-option
              v-for="item in modelSourceOptions"
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
      <el-table-column prop="modelType" label="模型类型" width="100">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getTypeTagType(scope.row.modelType)">
            {{ getTypeLabel(scope.row.modelType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="modelSource" label="来源" width="80">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.modelSource === 'builtin' ? 'success' : 'primary'">
            {{ scope.row.modelSource === 'builtin' ? '内置' : '自定义' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getStatusTagType(scope.row.status)">
            {{ getStatusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="parametersCount" label="参数量" width="100">
        <template slot-scope="scope">
          {{ formatParametersCount(scope.row.parametersCount) }}
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
          <p><strong>模型来源:</strong> {{ selectedModel.modelSource === 'builtin' ? '内置' : '自定义' }}</p>
          <p><strong>模型状态:</strong> {{ getStatusLabel(selectedModel.status) }}</p>
          <p><strong>参数量:</strong> {{ formatParametersCount(selectedModel.parametersCount) }}</p>
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
    <uploadModelForm
      :visible.sync="showUploadDialog"
      @success="handleUploadSuccess"
    />
  </el-dialog>
</template>

<script>
// 注意：这里需要根据实际的API路径调整
// import { getAllModel } from '@/api/system/model'
// import uploadModelForm from '../../model/module/form'

export default {
  name: 'ModelSelector',
  components: {
    // uploadModelForm
  },
  props: {
    visible: {
      type: Boolean,
      default: false
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
        modelSource: '',
        status: 'available' // 只显示可用的模型
      },
      modelTypeOptions: [
        { value: 'cnn', label: 'CNN' },
        { value: 'rnn', label: 'RNN' },
        { value: 'transformer', label: 'Transformer' },
        { value: 'linear', label: 'Linear' },
        { value: 'ensemble', label: 'Ensemble' }
      ],
      modelSourceOptions: [
        { value: 'builtin', label: '内置模型' },
        { value: 'custom', label: '自定义模型' }
      ]
    }
  },
  watch: {
    visible(val) {
      if (val) {
        this.resetSearch()
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
        status: this.searchForm.status
      }

      if (this.searchForm.blurry) {
        params.blurry = this.searchForm.blurry
      }
      if (this.searchForm.modelType) {
        params.modelType = this.searchForm.modelType
      }
      if (this.searchForm.modelSource) {
        params.modelSource = this.searchForm.modelSource
      }

      // TODO: 替换为实际的API调用
      // getAllModel(params).then(response => {
      //   const result = response.data || response
      //   this.modelList = result.content || result
      //   this.total = result.totalElements || this.modelList.length
      // }).catch(err => {
      //   console.error('加载模型列表失败:', err)
      //   this.$message.error('加载模型列表失败')
      // }).finally(() => {
      //   this.loading = false
      // })

      // 临时模拟数据
      setTimeout(() => {
        this.modelList = [
          {
            modelId: 1,
            modelName: 'ResNet-18',
            modelType: 'cnn',
            modelSource: 'builtin',
            status: 'available',
            parametersCount: 11689512,
            description: 'ResNet 18层深度残差网络',
            createTime: '2025-07-27 16:28:41'
          },
          {
            modelId: 2,
            modelName: 'BERT-Base',
            modelType: 'transformer',
            modelSource: 'builtin',
            status: 'available',
            parametersCount: 110104890,
            description: 'BERT基础版本',
            createTime: '2025-07-27 16:28:41'
          }
        ]
        this.total = this.modelList.length
        this.loading = false
      }, 500)
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
        modelType: '',
        modelSource: '',
        status: 'available'
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
      return model && model.status === 'available'
    },
    // 格式化参数量
    formatParametersCount(count) {
      if (!count || count === 0) return '未知'
      if (count >= 1000000000) {
        return (count / 1000000000).toFixed(1) + 'B'
      } else if (count >= 1000000) {
        return (count / 1000000).toFixed(1) + 'M'
      } else if (count >= 1000) {
        return (count / 1000).toFixed(1) + 'K'
      }
      return count.toString()
    },
    // 获取类型标签样式
    getTypeTagType(type) {
      const typeMap = {
        'cnn': 'primary',
        'rnn': 'success',
        'transformer': 'warning',
        'linear': 'info',
        'ensemble': 'danger'
      }
      return typeMap[type] || 'info'
    },
    // 获取类型标签文字
    getTypeLabel(type) {
      const typeMap = {
        'cnn': 'CNN',
        'rnn': 'RNN',
        'transformer': 'Transformer',
        'linear': 'Linear',
        'ensemble': 'Ensemble'
      }
      return typeMap[type] || type
    },
    // 获取状态标签样式
    getStatusTagType(status) {
      const statusMap = {
        'draft': 'info',
        'available': 'success',
        'maintenance': 'warning',
        'deprecated': 'danger'
      }
      return statusMap[status] || 'info'
    },
    // 获取状态标签文字
    getStatusLabel(status) {
      const statusMap = {
        'draft': '草稿',
        'available': '可用',
        'maintenance': '维护中',
        'deprecated': '已弃用'
      }
      return statusMap[status] || status
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
