<template>
  <div
    v-if="crud.props.searchToggle"
  >
    <el-input
      v-model="query.blurry"
      clearable
      size="small"
      placeholder="输入模型名称搜索"
      style="width: 200px;"
      class="filter-item"
      @keyup.enter.native="crud.toQuery"
    />
    <date-range-picker v-model="query.createTime" class="date-item" />
    <el-select
      v-model="query.modelSource"
      clearable
      size="small"
      placeholder="模型来源"
      class="filter-item"
      style="width: 110px"
      @change="crud.toQuery"
    >
      <el-option label="内置模型" value="builtin" />
      <el-option label="用户自定义" value="custom" />
    </el-select>
    <el-select
      v-model="query.modelType"
      clearable
      size="small"
      placeholder="模型类型"
      class="filter-item"
      style="width: 110px"
      @change="crud.toQuery"
    >
      <el-option label="CNN" value="cnn" />
      <el-option label="RNN" value="rnn" />
      <el-option label="Transformer" value="transformer" />
      <el-option label="Linear" value="linear" />
      <el-option label="其他" value="other" />
    </el-select>
    <el-select
      v-model="query.parseStatus"
      clearable
      size="small"
      placeholder="解析状态"
      class="filter-item"
      style="width: 110px"
      @change="crud.toQuery"
    >
      <el-option label="等待解析" value="pending" />
      <el-option label="解析中" value="parsing" />
      <el-option label="解析成功" value="success" />
      <el-option label="解析失败" value="failed" />
    </el-select>
    <el-select
      v-model="query.status"
      clearable
      size="small"
      placeholder="模型状态"
      class="filter-item"
      style="width: 100px"
      @change="crud.toQuery"
    >
      <el-option label="草稿" value="draft" />
      <el-option label="可用" value="available" />
      <el-option label="维护中" value="maintenance" />
      <el-option label="已弃用" value="deprecated" />
    </el-select>
    <rrOperation />
  </div>
</template>

<script>
import { header } from '@crud/crud'
import rrOperation from '@crud/RR.operation'
import DateRangePicker from '@/components/DateRangePicker'

export default {
  components: {rrOperation, DateRangePicker},
  mixins: [header()],
  props: {
    dict: {
      type: Object,
      required: true
    },
    permission: {
      type: Object,
      required: true
    }
  }
}
</script>
