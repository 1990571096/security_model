<template>
  <div
    v-if="crud.props.searchToggle"
  >
    <el-input v-model="query.blurry" clearable size="small" placeholder="输入算法名称搜索" style="width: 200px;" class="filter-item" @keyup.enter.native="crud.toQuery" />
    <el-select v-model="query.algorithmType" clearable size="small" placeholder="算法类型" class="filter-item" style="width: 120px" @change="crud.toQuery">
      <el-option v-for="item in algorithmTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <el-select v-model="query.defenseStage" clearable size="small" placeholder="防御阶段" class="filter-item" style="width: 120px" @change="crud.toQuery">
      <el-option v-for="item in defenseStageOptions" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <el-select v-model="query.algorithmCategory" clearable size="small" placeholder="算法分类" class="filter-item" style="width: 120px" @change="crud.toQuery">
      <el-option v-for="item in algorithmCategoryOptions" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <el-select v-model="query.enabled" clearable size="small" placeholder="状态" class="filter-item" style="width: 90px" @change="crud.toQuery">
      <el-option v-for="item in enabledOptions" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <date-range-picker v-model="query.createTime" class="date-item" />
    <rrOperation />
  </div>
</template>

<script>
import { header } from '@crud/crud'
import rrOperation from '@crud/RR.operation'
import DateRangePicker from '@/components/DateRangePicker'

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
      algorithmTypeOptions: [
        { value: 'data_cleaning', label: '数据清洗' },
        { value: 'backdoor_detection', label: '后门检测' },
        { value: 'model_purification', label: '模型净化' }
      ],
      defenseStageOptions: [
        { value: 'pre', label: '训练前' },
        { value: 'during', label: '训练中' },
        { value: 'post', label: '训练后' }
      ],
      algorithmCategoryOptions: [
        { value: 'statistical', label: '统计方法' },
        { value: 'ml_based', label: '机器学习' },
        { value: 'deep_learning', label: '深度学习' }
      ],
      enabledOptions: [
        { value: true, label: '启用' },
        { value: false, label: '禁用' }
      ]
    }
  }
}
</script>
