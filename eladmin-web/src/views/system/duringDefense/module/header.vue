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
      v-model="query.datasetId"
      clearable
      size="small"
      placeholder="数据集"
      class="filter-item"
      style="width: 150px"
      @change="crud.toQuery"
    >
      <el-option
        v-for="item in datasetOptions"
        :key="item.datasetId"
        :label="item.datasetName"
        :value="item.datasetId"
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
import { getAllDataset } from '@/api/system/dataset'
// 注意：这里需要根据实际的API路径调整
// import { getAllModel } from '@/api/system/model'

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
      algorithmOptions: [],
      datasetOptions: [],
      modelOptions: []
    }
  },
  created() {
    this.loadFilterOptions()
  },
  methods: {
    // 加载筛选选项
    loadFilterOptions() {
      // 加载训练中防御算法
      this.loadAlgorithms()
      // 加载数据集选项
      this.loadDatasets()
      // 加载模型选项
      this.loadModels()
    },
    // 加载算法选项 - 只加载训练中防御算法
    loadAlgorithms() {
      getAlgorithmsByStage('during').then(response => {
        this.algorithmOptions = response.data || response
      }).catch(err => {
        console.error('加载算法列表失败:', err)
      })
    },
    // 加载数据集选项
    loadDatasets() {
      getAllDataset().then(response => {
        const datasets = response.data || response
        // 只显示可用的数据集
        this.datasetOptions = Array.isArray(datasets)
          ? datasets.filter(item => item.enabled && item.parseStatus === 'success')
          : []
      }).catch(err => {
        console.error('加载数据集列表失败:', err)
      })
    },
    // 加载模型选项
    loadModels() {
      // TODO: 替换为实际的API调用
      // getAllModel().then(response => {
      //   const models = response.data || response
      //   // 只显示可用的模型
      //   this.modelOptions = Array.isArray(models)
      //     ? models.filter(item => item.status === 'available')
      //     : (models.content || []).filter(item => item.status === 'available')
      // }).catch(err => {
      //   console.error('加载模型列表失败:', err)
      // })

      // 临时模拟数据
      this.modelOptions = [
        { modelId: 1, modelName: 'ResNet-18' },
        { modelId: 2, modelName: 'BERT-Base' },
        { modelId: 3, modelName: 'VGG-16' }
      ]
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
