<template>
  <div v-if="crud.props.searchToggle">
    <!-- 搜索 -->
    <el-input
      v-model="query.blurry"
      clearable
      size="small"
      placeholder="输入任务名称搜索"
      style="width: 200px;"
      class="filter-item"
      @keyup.enter.native="crud.toQuery"
    />
    <el-select
      v-model="query.status"
      clearable
      size="small"
      placeholder="任务状态"
      class="filter-item"
      style="width: 120px"
      @change="crud.toQuery"
    >
      <el-option
        v-for="item in statusOptions"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>
    <el-select
      v-model="query.defenseResult"
      clearable
      size="small"
      placeholder="检测结果"
      class="filter-item"
      style="width: 120px"
      @change="crud.toQuery"
    >
      <el-option
        v-for="item in defenseResultOptions"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>
    <el-select
      v-model="query.algorithmId"
      clearable
      size="small"
      placeholder="防御算法"
      class="filter-item"
      style="width: 150px"
      @change="crud.toQuery"
    >
      <el-option
        v-for="item in algorithmOptions"
        :key="item.algorithmId"
        :label="item.algorithmName"
        :value="item.algorithmId"
      />
    </el-select>
    <el-select
      v-model="query.inputModelId"
      clearable
      size="small"
      placeholder="输入模型"
      class="filter-item"
      style="width: 150px"
      @change="crud.toQuery"
    >
      <el-option
        v-for="item in modelOptions"
        :key="item.modelId"
        :label="item.modelName"
        :value="item.modelId"
      />
    </el-select>
    <date-range-picker v-model="query.createTime" class="date-item" />
    <rrOperation />
  </div>
</template>

<script>
import { header } from '@crud/crud'
import rrOperation from '@crud/RR.operation'
import DateRangePicker from '@/components/DateRangePicker'
import { getAlgorithmsByStage } from '@/api/system/algorithm'
import { getAllModel } from '@/api/system/model'

export default {
  components: { rrOperation, DateRangePicker },
  mixins: [header()],
  props: {
    permission: {
      type: Object,
      required: true
    }
  },
  data() {
    return {
      statusOptions: [
        { value: 'pending', label: '等待中' },
        { value: 'running', label: '运行中' },
        { value: 'success', label: '成功' },
        { value: 'failed', label: '失败' }
      ],
      defenseResultOptions: [
        { value: 'clean', label: '清洁' },
        { value: 'purified', label: '已净化' },
        { value: 'infected', label: '发现后门' },
        { value: 'failed', label: '检测失败' }
      ],
      algorithmOptions: [],
      modelOptions: []
    }
  },
  created() {
    this.loadAlgorithms()
    this.loadModels()
  },
  methods: {
    // 加载算法选项
    loadAlgorithms() {
      getAlgorithmsByStage('post').then(response => {
        this.algorithmOptions = response.data || response
      }).catch(err => {
        console.error('加载算法列表失败:', err)
      })
    },
    // 加载模型选项
    loadModels() {
      getAllModel().then(response => {
        const models = response.data || response
        // 只显示可用的模型
        if (Array.isArray(models)) {
          this.modelOptions = models.filter(model =>
            model.enabled && (model.status === 'available' || model.status === 'inference')
          )
        } else {
          this.modelOptions = []
        }
      }).catch(err => {
        console.error('加载模型列表失败:', err)
      })
    }
  }
}
</script>

<style scoped>
.filter-item {
  display: inline-block;
  vertical-align: middle;
  margin-bottom: 10px;
  margin-right: 10px;
}

.date-item {
  margin-right: 10px;
}
</style>
