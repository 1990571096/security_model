<template>
  <div class="app-container">
    <!--手动创建数据集表单-->
    <el-dialog append-to-body :close-on-click-modal="false" :before-close="crud.cancelCU" :visible="crud.status.cu > 0" :title="crud.status.title" width="500px">
      <el-form ref="form" :model="form" :rules="rules" size="small" label-width="100px">
        <el-form-item label="数据集名称" prop="datasetName">
          <el-input v-model="form.datasetName" style="width: 370px;" />
        </el-form-item>
        <el-form-item label="数据集描述">
          <el-input v-model="form.description" type="textarea" :rows="3" style="width: 370px;" />
        </el-form-item>
        <el-form-item label="数据集类型" prop="datasetType">
          <el-select v-model="form.datasetType" placeholder="请选择数据集类型" style="width: 370px;">
            <el-option
              v-for="item in datasetTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="文件路径" prop="filePath">
          <el-input v-model="form.filePath" placeholder="可选：输入数据集文件路径" style="width: 370px;" />
          <div style="color: #999; font-size: 12px; margin-top: 5px;">
            可选项：如果数据集文件存储在服务器上，可以输入文件路径
          </div>
        </el-form-item>
        <el-form-item label="启用状态" prop="enabled">
          <el-radio
            v-for="item in enabledOptions"
            :key="item.value"
            v-model="form.enabled"
            :label="item.value"
          >
            {{ item.label }}
          </el-radio>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="text" @click="crud.cancelCU">取消</el-button>
        <el-button :loading="crud.status.cu === 2" type="primary" @click="crud.submitCU">确认</el-button>
      </div>
    </el-dialog>

    <!-- 数据集列表 -->
    <el-row :gutter="10">
      <el-col :xs="24" :sm="24" :md="11" :lg="13" :xl="13" style="margin-bottom: 10px">
        <el-card class="box-card">
          <!--工具栏-->
          <div class="head-container">
            <div v-if="crud.props.searchToggle">
              <!-- 搜索 -->
              <el-input v-model="query.blurry" clearable size="small" placeholder="输入名称搜索" style="width: 200px;" class="filter-item" @keyup.enter.native="crud.toQuery" />
              <el-select v-model="query.datasetType" clearable size="small" placeholder="数据集类型" class="filter-item" style="width: 120px" @change="crud.toQuery">
                <el-option v-for="item in datasetTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-select v-model="query.parseStatus" clearable size="small" placeholder="解析状态" class="filter-item" style="width: 120px" @change="crud.toQuery">
                <el-option v-for="item in parseStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <rrOperation />
            </div>
            <!-- 自定义操作栏：上传按钮 + 标准CRUD按钮 -->
            <div class="crud-opts">
              <span class="crud-opts-left">
                <!-- 上传数据集按钮 -->
                <el-button
                  v-if="checkPer(['admin','dataset:add'])"
                  class="filter-item"
                  size="mini"
                  type="success"
                  icon="el-icon-upload"
                  @click="showUploadDialog = true"
                >上传数据集</el-button>

                <!-- 刷新 -->
                <el-button
                  class="filter-item"
                  size="mini"
                  type="success"
                  icon="el-icon-refresh"
                  @click="crud.refresh()"
                />
              </span>

              <span class="crud-opts-right">
                <!-- 标准CRUD操作按钮 -->
                <el-button
                  v-if="checkPer(['admin','dataset:add'])"
                  class="filter-item"
                  size="mini"
                  type="primary"
                  icon="el-icon-plus"
                  @click="crud.toAdd"
                >手动新增</el-button>
                <el-button
                  v-if="checkPer(['admin','dataset:edit']) && crud.selections.length === 1"
                  class="filter-item"
                  size="mini"
                  type="primary"
                  icon="el-icon-edit"
                  @click="crud.toEdit(crud.selections[0])"
                />
                <el-button
                  slot="reference"
                  v-if="checkPer(['admin','dataset:del']) && crud.selections.length > 0"
                  class="filter-item"
                  type="danger"
                  icon="el-icon-delete"
                  size="mini"
                  @click="crud.toDelete(crud.selections)"
                />
              </span>
            </div>
          </div>
          <!--表格渲染-->
          <el-table ref="table" v-loading="crud.loading" :data="crud.data" highlight-current-row style="width: 100%;" @selection-change="crud.selectionChangeHandler" @current-change="handleCurrentChange">
            <el-table-column type="selection" width="55" />
            <el-table-column :show-overflow-tooltip="true" prop="datasetName" label="数据集名称" />
            <el-table-column prop="datasetType" label="类型" width="60px">
              <template slot-scope="scope">
                <el-tag size="mini" :type="getTypeTagType(scope.row.datasetType)">
                  {{ getTypeLabel(scope.row.datasetType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="parseStatus" label="解析状态" width="90px">
              <template slot-scope="scope">
                <el-tag size="mini" :type="getStatusTagType(scope.row.parseStatus)">
                  {{ getStatusLabel(scope.row.parseStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sampleCount" label="样本数" width="70px" />
            <el-table-column prop="enabled" label="状态" width="60px" align="center">
              <template slot-scope="scope">
                <el-switch
                  v-model="scope.row.enabled"
                  active-color="#409EFF"
                  inactive-color="#F56C6C"
                  @change="changeEnabled(scope.row, scope.row.enabled)"
                />
              </template>
            </el-table-column>
            <el-table-column v-if="checkPer(['admin','dataset:edit','dataset:del'])" label="操作" width="130px" align="center" fixed="right">
              <template slot-scope="scope">
                <udOperation
                  :data="scope.row"
                  :permission="permission"
                />
              </template>
            </el-table-column>
          </el-table>
          <!--分页组件-->
          <pagination />
        </el-card>
      </el-col>
      <!-- 数据集详情 -->
      <el-col :xs="24" :sm="24" :md="11" :lg="10" :xl="10">
        <el-card class="box-card">
          <div slot="header" class="clearfix">
            <span>数据集详情</span>
            <el-button
              v-if="checkPer(['admin','dataset:edit']) && this.$refs.datasetDetail && this.$refs.datasetDetail.selectedDataset"
              class="filter-item"
              size="mini"
              style="float: right;padding: 4px 10px; margin-left: 10px;"
              type="success"
              icon="el-icon-refresh"
              @click="startParsing"
            >启动解析</el-button>
          </div>
          <datasetDetail ref="datasetDetail" :permission="permission" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 上传数据集对话框 -->
    <uploadForm
      :visible.sync="showUploadDialog"
      @success="handleUploadSuccess"
    />
  </div>
</template>

<script>
import datasetDetail from './datasetDetail'
import uploadForm from './module/form'
import crudDataset from '@/api/system/dataset'
import CRUD, { presenter, header, form } from '@crud/crud'
// import crudOperation from '@crud/CRUD.operation'
import pagination from '@crud/Pagination'
import rrOperation from '@crud/RR.operation'
import udOperation from '@crud/UD.operation'

const defaultForm = {
  datasetId: null,
  datasetName: '',
  description: '',
  datasetType: '',
  enabled: true,
  filePath: ''
}

export default {
  name: 'Dataset',
  // components: { crudOperation, pagination, rrOperation, udOperation, datasetDetail, uploadForm },
  components: { pagination, rrOperation, udOperation, datasetDetail, uploadForm },
  cruds() {
    return [
      CRUD({
        title: '数据集',
        url: 'api/dataset',
        crudMethod: { ...crudDataset }
      })
    ]
  },
  mixins: [presenter(), header(), form(defaultForm)],
  data() {
    return {
      showUploadDialog: false,
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
      ],
      enabledOptions: [
        { value: true, label: '启用' },
        { value: false, label: '禁用' }
      ],
      rules: {
        datasetName: [
          { required: true, message: '请输入数据集名称', trigger: 'blur' }
        ],
        datasetType: [
          { required: true, message: '请选择数据集类型', trigger: 'change' }
        ]
      },
      permission: {
        add: ['admin', 'dataset:add'],
        edit: ['admin', 'dataset:edit'],
        del: ['admin', 'dataset:del']
      }
    }
  },
  methods: {
    // 获取数据前清空详情
    [CRUD.HOOK.beforeRefresh]() {
      if (this.$refs.datasetDetail) {
        this.$refs.datasetDetail.selectedDataset = null
      }
      return true
    },
    // 选中数据集后，设置数据集详情数据
    handleCurrentChange(val) {
      if (val) {
        this.$refs.datasetDetail.selectedDataset = val
      }
    },
    // 上传成功回调
    handleUploadSuccess(data) {
      this.showUploadDialog = false
      this.$message.success('数据集上传成功!')
      this.crud.refresh()
    },
    // 改变启用状态
    changeEnabled(data, val) {
      this.$confirm('此操作将 "' + (val ? '启用' : '禁用') + '" ' + data.datasetName + ', 是否继续？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        crudDataset.edit(data).then(() => {
          this.crud.notify((val ? '启用' : '禁用') + '成功', 'success')
        }).catch(err => {
          data.enabled = !data.enabled
          console.log(err.data.message)
        })
      }).catch(() => {
        data.enabled = !data.enabled
      })
    },
    // 启动解析
    startParsing() {
      const dataset = this.$refs.datasetDetail.selectedDataset
      if (!dataset) {
        this.$message.warning('请先选择一个数据集')
        return
      }
      if (dataset.parseStatus === 'parsing') {
        this.$message.warning('数据集正在解析中')
        return
      }

      this.$confirm('确定要启动数据集解析吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        crudDataset.startParsing(dataset.datasetId).then(() => {
          this.$message.success('启动解析成功')
          this.crud.refresh()
        }).catch(err => {
          this.$message.error('启动解析失败：' + err.message)
        })
      })
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
.crud-opts {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.crud-opts-left, .crud-opts-right {
  display: flex;
  align-items: center;
}

.crud-opts-left .filter-item,
.crud-opts-right .filter-item {
  margin-right: 10px;
}
.crud-opts-left .filter-item:last-child,
.crud-opts-right .filter-item:last-child {
  margin-right: 0;
}
</style>
