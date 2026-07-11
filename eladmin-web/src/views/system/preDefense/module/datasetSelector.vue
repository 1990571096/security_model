<template>
  <el-dialog
    :visible="visible"
    title="选择数据集"
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
            placeholder="搜索数据集名称"
            size="small"
            clearable
            @keyup.enter.native="loadDatasets"
          >
            <i slot="prefix" class="el-input__icon el-icon-search"></i>
          </el-input>
        </el-col>
        <el-col :span="4">
          <el-select
            v-model="searchForm.datasetType"
            placeholder="数据集类型"
            size="small"
            clearable
            @change="loadDatasets"
          >
            <el-option
              v-for="item in datasetTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-select
            v-model="searchForm.parseStatus"
            placeholder="解析状态"
            size="small"
            clearable
            @change="loadDatasets"
          >
            <el-option
              v-for="item in parseStatusOptions"
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
            @click="loadDatasets"
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
            上传数据集
          </el-button>
        </el-col>
        <el-col :span="4">
          <el-button
            type="info"
            size="small"
            icon="el-icon-refresh"
            @click="refreshDatasets"
          >
            刷新
          </el-button>
        </el-col>
      </el-row>
    </div>

    <!-- 数据集列表表格 -->
    <el-table
      ref="datasetTable"
      v-loading="loading"
      :data="datasetList"
      style="width: 100%; margin-top: 15px;"
      highlight-current-row
      @current-change="handleCurrentChange"
      @row-dblclick="handleRowDoubleClick"
      height="400px"
    >
      <el-table-column type="index" width="50" label="#" />
      <el-table-column prop="datasetName" label="数据集名称" min-width="150">
        <template slot-scope="scope">
          <el-tooltip :content="scope.row.description || '无描述'" placement="top">
            <span>{{ scope.row.datasetName }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="datasetType" label="类型" width="100">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getTypeTagType(scope.row.datasetType)">
            {{ getTypeLabel(scope.row.datasetType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="parseStatus" label="解析状态" width="100">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getStatusTagType(scope.row.parseStatus)">
            {{ getStatusLabel(scope.row.parseStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sampleCount" label="样本数" width="80" />
      <el-table-column prop="fileSize" label="文件大小" width="100">
        <template slot-scope="scope">
          {{ formatFileSize(scope.row.fileSize) }}
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="150" />
      <el-table-column label="可用性" width="80" align="center">
        <template slot-scope="scope">
          <el-tag
            size="mini"
            :type="isDatasetAvailable(scope.row) ? 'success' : 'danger'"
          >
            {{ isDatasetAvailable(scope.row) ? '可用' : '不可用' }}
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

    <!-- 选中数据集信息 -->
    <div v-if="selectedDataset" class="selected-info">
      <el-alert
        :title="`已选择数据集: ${selectedDataset.datasetName}`"
        type="info"
        :closable="false"
        show-icon
      >
        <template slot="default">
          <p><strong>数据集类型:</strong> {{ getTypeLabel(selectedDataset.datasetType) }}</p>
          <p><strong>解析状态:</strong> {{ getStatusLabel(selectedDataset.parseStatus) }}</p>
          <p><strong>样本数量:</strong> {{ selectedDataset.sampleCount || '未知' }}</p>
          <p v-if="selectedDataset.description"><strong>描述:</strong> {{ selectedDataset.description }}</p>
        </template>
      </el-alert>
    </div>

    <!-- 对话框底部按钮 -->
    <div slot="footer" class="dialog-footer">
      <el-button @click="handleClose">取消</el-button>
      <el-button
        type="primary"
        :disabled="!selectedDataset || !isDatasetAvailable(selectedDataset)"
        @click="handleConfirm"
      >
        确认选择
      </el-button>
    </div>

    <!-- 上传数据集对话框 -->
    <uploadForm
      :visible.sync="showUploadDialog"
      @success="handleUploadSuccess"
    />
  </el-dialog>
</template>

<script>
import { getAllDataset } from '@/api/system/dataset'
import uploadForm from '../../dataset/module/form'

export default {
  name: 'DatasetSelector',
  components: { uploadForm },
  props: {
    visible: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      loading: false,
      datasetList: [],
      selectedDataset: null,
      showUploadDialog: false,
      total: 0,
      searchForm: {
        page: 1,
        size: 10,
        blurry: '',
        datasetType: '',
        parseStatus: '',
        enabled: true // 只显示启用的数据集
      },
      datasetTypeOptions: [
        { value: 'image', label: '图像数据' },
        { value: 'text', label: '文本数据' },
        { value: 'tabular', label: '表格数据' }
      ],
      parseStatusOptions: [
        { value: 'pending', label: '等待解析' },
        { value: 'parsing', label: '解析中' },
        { value: 'success', label: '解析成功' },
        { value: 'failed', label: '解析失败' }
      ]
    }
  },
  watch: {
    visible(val) {
      if (val) {
        this.resetSearch()
        this.loadDatasets()
      } else {
        this.selectedDataset = null
      }
    }
  },
  methods: {
    // 加载数据集列表
    loadDatasets() {
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
      if (this.searchForm.datasetType) {
        params.datasetType = this.searchForm.datasetType
      }
      if (this.searchForm.parseStatus) {
        params.parseStatus = this.searchForm.parseStatus
      }

      getAllDataset().then(response => {
        const result = response.data || response
        this.datasetList = result.content || result
        this.total = result.totalElements || this.datasetList.length
      }).catch(err => {
        console.error('加载数据集列表失败:', err)
        this.$message.error('加载数据集列表失败')
      }).finally(() => {
        this.loading = false
      })
    },
    // 刷新数据集列表
    refreshDatasets() {
      this.resetSearch()
      this.loadDatasets()
    },
    // 重置搜索条件
    resetSearch() {
      this.searchForm = {
        page: 1,
        size: 10,
        blurry: '',
        datasetType: '',
        parseStatus: '',
        enabled: true
      }
      this.selectedDataset = null
    },
    // 表格当前行改变
    handleCurrentChange(val) {
      this.selectedDataset = val
    },
    // 双击行选择
    handleRowDoubleClick(row) {
      if (this.isDatasetAvailable(row)) {
        this.selectedDataset = row
        this.handleConfirm()
      } else {
        this.$message.warning('该数据集不可用，请选择其他数据集')
      }
    },
    // 分页大小改变
    handleSizeChange(val) {
      this.searchForm.size = val
      this.searchForm.page = 1
      this.loadDatasets()
    },
    // 当前页改变
    handlePageChange(val) {
      this.searchForm.page = val
      this.loadDatasets()
    },
    // 上传成功回调
    handleUploadSuccess(dataset) {
      this.showUploadDialog = false
      this.$message.success('数据集上传成功!')
      this.loadDatasets()
    },
    // 确认选择
    handleConfirm() {
      if (!this.selectedDataset) {
        this.$message.warning('请先选择一个数据集')
        return
      }

      if (!this.isDatasetAvailable(this.selectedDataset)) {
        this.$message.warning('所选数据集不可用，请选择已解析成功且启用的数据集')
        return
      }

      this.$emit('select', this.selectedDataset)
    },
    // 关闭对话框
    handleClose() {
      this.$emit('update:visible', false)
    },
    // 判断数据集是否可用
    isDatasetAvailable(dataset) {
      return dataset && dataset.enabled && dataset.parseStatus === 'success'
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
        'image': 'primary',
        'text': 'success',
        'tabular': 'warning'
      }
      return typeMap[type] || 'info'
    },
    // 获取类型标签文字
    getTypeLabel(type) {
      const typeMap = {
        'image': '图像',
        'text': '文本',
        'tabular': '表格'
      }
      return typeMap[type] || type
    },
    // 获取状态标签样式
    getStatusTagType(status) {
      const statusMap = {
        'pending': 'info',
        'parsing': 'warning',
        'success': 'success',
        'failed': 'danger'
      }
      return statusMap[status] || 'info'
    },
    // 获取状态标签文字
    getStatusLabel(status) {
      const statusMap = {
        'pending': '等待解析',
        'parsing': '解析中',
        'success': '解析成功',
        'failed': '解析失败'
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
