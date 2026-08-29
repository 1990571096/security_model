<template>
  <div class="app-container">
    <!--工具栏-->
    <div class="head-container">
      <el-alert
        title="当前平台算法范围：LDD 训练前数据清洗"
        type="info"
        :closable="false"
        show-icon
        class="scope-notice"
      />
      <eHeader :permission="permission" />
      <crudOperation :permission="permission" />
    </div>
    <!--表格渲染-->
    <el-table ref="table" v-loading="crud.loading" :data="lddAlgorithms" style="width: 100%;" @selection-change="crud.selectionChangeHandler">
      <el-table-column type="selection" width="55" />
      <el-table-column :show-overflow-tooltip="true" prop="algorithmName" label="算法名称" width="120px"/>
      <el-table-column prop="algorithmType" label="算法类型" width="120px">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getAlgorithmTypeTagType(scope.row.algorithmType)">
            {{ getAlgorithmTypeLabel(scope.row.algorithmType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="defenseStage" label="防御阶段" width="100px">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getDefenseStageTagType(scope.row.defenseStage)">
            {{ getDefenseStageLabel(scope.row.defenseStage) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="algorithmCategory" label="算法分类" width="120px">
        <template slot-scope="scope">
          <el-tag size="mini" :type="getAlgorithmCategoryTagType(scope.row.algorithmCategory)" v-if="scope.row.algorithmCategory">
            {{ getAlgorithmCategoryLabel(scope.row.algorithmCategory) }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="enabled" label="状态" width="80px" align="center">
        <template slot-scope="scope">
          <el-switch
            v-model="scope.row.enabled"
            active-color="#409EFF"
            inactive-color="#F56C6C"
            @change="changeEnabled(scope.row, scope.row.enabled)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建日期" width="150px" />
      <!--   编辑与删除   -->
      <el-table-column
        v-if="checkPer(['admin','algorithm:edit','algorithm:del'])"
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
import crudAlgorithm from '@/api/system/algorithm'
import eHeader from './module/header'
import eForm from './module/form'
import CRUD, { presenter } from '@crud/crud'
import crudOperation from '@crud/CRUD.operation'
import pagination from '@crud/Pagination'
import udOperation from '@crud/UD.operation'

export default {
  name: 'Algorithm',
  components: { eHeader, eForm, crudOperation, pagination, udOperation },
  cruds() {
    return CRUD({
      title: '算法',
      url: 'api/algorithm',
      crudMethod: { ...crudAlgorithm }
    })
  },
  mixins: [presenter()],
  data() {
    return {
      permission: {
        edit: ['admin', 'algorithm:edit']
      }
    }
  },
  computed: {
    lddAlgorithms() {
      return (this.crud.data || []).filter(item => item.implementationPath === 'vab://pre-defense/ldd')
    }
  },
  methods: {
    // 改变启用状态
    changeEnabled(data, val) {
      this.$confirm('此操作将 "' + (val ? '启用' : '禁用') + '" ' + data.algorithmName + ', 是否继续？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        crudAlgorithm.edit(data).then(() => {
          this.crud.notify((val ? '启用' : '禁用') + '成功', 'success')
        }).catch(err => {
          data.enabled = !data.enabled
          console.log(err.data.message)
        })
      }).catch(() => {
        data.enabled = !data.enabled
      })
    },
    // 获取算法类型标签样式
    getAlgorithmTypeTagType(type) {
      const typeMap = {
        'data_cleaning': 'primary',
        'backdoor_detection': 'success',
        'model_purification': 'warning'
      }
      return typeMap[type] || 'info'
    },
    // 获取算法类型标签文字
    getAlgorithmTypeLabel(type) {
      const typeMap = {
        'data_cleaning': '数据清洗',
        'backdoor_detection': '后门检测',
        'model_purification': '模型净化'
      }
      return typeMap[type] || type
    },
    // 获取防御阶段标签样式
    getDefenseStageTagType(stage) {
      const stageMap = {
        'pre': 'success',
        'during': 'warning',
        'post': 'danger'
      }
      return stageMap[stage] || 'info'
    },
    // 获取防御阶段标签文字
    getDefenseStageLabel(stage) {
      const stageMap = {
        'pre': '训练前',
        'during': '训练中',
        'post': '训练后'
      }
      return stageMap[stage] || stage
    },
    // 获取算法分类标签样式
    getAlgorithmCategoryTagType(category) {
      const categoryMap = {
        'statistical': 'primary',
        'ml_based': 'success',
        'deep_learning': 'warning'
      }
      return categoryMap[category] || 'info'
    },
    // 获取算法分类标签文字
    getAlgorithmCategoryLabel(category) {
      const categoryMap = {
        'statistical': '统计方法',
        'ml_based': '机器学习',
        'deep_learning': '深度学习'
      }
      return categoryMap[category] || category
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
::v-deep .el-input-number .el-input__inner {
  text-align: left;
}
.scope-notice {
  margin-bottom: 12px;
}
</style>
