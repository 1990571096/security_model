<template>
  <div class="app-container">
    <!--工具栏-->
    <div class="head-container">
      <eHeader :dict="dict" :permission="permission" />
      <crudOperation :permission="permission" />
    </div>
    <!--表格渲染-->
    <el-table ref="table" v-loading="crud.loading" :data="crud.data" style="width: 100%;" @selection-change="crud.selectionChangeHandler">
      <el-table-column type="selection" width="55" />
      <el-table-column prop="modelName" label="模型名称" width="130" />
      <el-table-column prop="modelSource" label="模型来源" width="80" align="center">
        <template slot-scope="scope">
          <el-tag :type="scope.row.modelSource === 'builtin' ? 'success' : 'primary'" size="small">
            {{ scope.row.modelSource === 'builtin' ? '内置' : '自定义' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="modelType" label="模型类型" width="120" align="center">
        <template slot-scope="scope">
          <el-tag :type="getModelTypeTagType(scope.row.modelType)" size="small">
            {{ scope.row.modelType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="modelCategory" label="模型类别" width="150" align="center">
        <template slot-scope="scope">
          <el-tag type="info" size="small" v-if="scope.row.modelCategory">
            {{ scope.row.modelCategory }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="inputShape" label="输入形状" width="120" align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.inputShape">{{ scope.row.inputShape }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="outputShape" label="输出形状" width="120" align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.outputShape">{{ scope.row.outputShape }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="parametersCount" label="参数数量" width="80" align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.parametersCount">{{ formatParameters(scope.row.parametersCount) }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="parseStatus" label="解析状态" width="100" align="center">
        <template slot-scope="scope">
          <el-tag :type="getParseStatusTagType(scope.row.parseStatus)" size="small">
            {{ getParseStatusLabel(scope.row.parseStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="模型状态" width="100" align="center">
        <template slot-scope="scope">
          <el-tag :type="getStatusTagType(scope.row.status)" size="small">
            {{ getStatusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="模型详情" width="80" align="center">
        <template slot-scope="scope">
          <el-tooltip :content="scope.row.description || '暂无描述'" placement="top" effect="dark">
            <el-button type="text" size="small" icon="el-icon-info">详情</el-button>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建日期" width="140" />
      <!--   编辑与删除   -->
      <el-table-column
        v-if="checkPer(['admin','model:edit','model:del'])"
        label="操作"
        width="130px"
        align="center"
        fixed="right"
      >
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
    <!--表单渲染-->
    <eForm />
  </div>
</template>

<script>
import crudModel from '@/api/system/model'
import eHeader from './module/header'
import eForm from './module/form'
import CRUD, { presenter } from '@crud/crud'
import crudOperation from '@crud/CRUD.operation'
import pagination from '@crud/Pagination'
import udOperation from '@crud/UD.operation'

export default {
  name: 'Model',
  components: { eHeader, eForm, crudOperation, pagination, udOperation },
  cruds() {
    return CRUD({
      title: '模型',
      url: 'api/model',
      crudMethod: { ...crudModel },
      idField: 'modelId'
    })
  },
  mixins: [presenter()],
  // 数据字典
  dicts: ['model_source', 'model_type', 'model_status', 'parse_status'],
  data() {
    return {
      permission: {
        add: ['admin', 'model:add'],
        edit: ['admin', 'model:edit'],
        del: ['admin', 'model:del']
      }
    }
  },
  methods: {
    // 获取模型类型标签样式
    getModelTypeTagType(modelType) {
      const typeMap = {
        'cnn': 'success',
        'rnn': 'warning',
        'transformer': 'danger',
        'linear': 'info'
      }
      return typeMap[modelType] || 'primary'
    },
    // 获取解析状态标签样式
    getParseStatusTagType(parseStatus) {
      const statusMap = {
        'pending': 'info',
        'parsing': 'warning',
        'success': 'success',
        'failed': 'danger'
      }
      return statusMap[parseStatus] || 'info'
    },
    // 获取解析状态显示文本
    getParseStatusLabel(parseStatus) {
      const labelMap = {
        'pending': '等待解析',
        'parsing': '解析中',
        'success': '解析成功',
        'failed': '解析失败'
      }
      return labelMap[parseStatus] || parseStatus
    },
    // 获取模型状态标签样式
    getStatusTagType(status) {
      const statusMap = {
        'draft': 'info',
        'available': 'success',
        'maintenance': 'warning',
        'deprecated': 'danger'
      }
      return statusMap[status] || 'info'
    },
    // 获取模型状态显示文本
    getStatusLabel(status) {
      const labelMap = {
        'draft': '草稿',
        'available': '可用',
        'maintenance': '维护中',
        'deprecated': '已弃用'
      }
      return labelMap[status] || status
    },
    // 格式化参数数量显示
    formatParameters(count) {
      if (count >= 1000000000) {
        return (count / 1000000000).toFixed(1) + 'B'
      } else if (count >= 1000000) {
        return (count / 1000000).toFixed(1) + 'M'
      } else if (count >= 1000) {
        return (count / 1000).toFixed(1) + 'K'
      }
      return count.toString()
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
::v-deep .el-tag {
  margin: 2px;
}
</style>
