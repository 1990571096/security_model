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
      placeholder="清洗算法"
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
    <date-range-picker v-model="query.createTime" class="date-item" />
    <rrOperation />
  </div>
</template>

<script>
import { header } from '@crud/crud'
import rrOperation from '@crud/RR.operation'
import DateRangePicker from '@/components/DateRangePicker'
import { getAlgorithmsByStage } from '@/api/system/algorithm'

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
        { value: 'failed', label: '失败' },
        { value: 'canceled', label: '已取消' }
      ],
      algorithmOptions: []
    }
  },
  created() {
    this.loadAlgorithms()
  },
  methods: {
    // 加载算法选项
    loadAlgorithms() {
      getAlgorithmsByStage('pre').then(response => {
        const algorithms = response.data || response || []
        this.algorithmOptions = algorithms.filter(item => item.implementationPath === 'vab://pre-defense/ldd')
      }).catch(err => {
        console.error('加载算法列表失败:', err)
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
