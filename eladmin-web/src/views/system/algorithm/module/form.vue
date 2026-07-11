<template>
  <el-dialog
    append-to-body
    :close-on-click-modal="false"
    :before-close="crud.cancelCU"
    :visible="crud.status.cu > 0"
    :title="crud.status.title"
    width="900px"
  >
    <el-form
      ref="form"
      :model="form"
      :rules="rules"
      size="small"
      label-width="100px"
    >
      <el-row>
        <el-col :span="24">
          <el-form-item
            label="算法名称"
            prop="algorithmName"
          >
            <el-input
              v-model="form.algorithmName"
              placeholder="请输入算法名称"
              style="width: 500px;"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row>
        <el-col :span="24">
          <el-form-item
            label="算法描述"
          >
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="3"
              placeholder="请输入算法描述"
              style="width: 500px;"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row>
        <el-col :span="12">
          <el-form-item
            label="算法类型"
            prop="algorithmType"
          >
            <el-select
              v-model="form.algorithmType"
              placeholder="请选择算法类型"
              style="width: 220px;"
            >
              <el-option
                v-for="item in algorithmTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item
            label="防御阶段"
            prop="defenseStage"
          >
            <el-select
              v-model="form.defenseStage"
              placeholder="请选择防御阶段"
              style="width: 220px;"
            >
              <el-option
                v-for="item in defenseStageOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row>
        <el-col :span="12">
          <el-form-item
            label="算法分类"
          >
            <el-select
              v-model="form.algorithmCategory"
              placeholder="请选择算法分类"
              clearable
              style="width: 220px;"
            >
              <el-option
                v-for="item in algorithmCategoryOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item
            label="启用状态"
            prop="enabled"
          >
            <el-radio
              v-for="item in enabledOptions"
              :key="item.value"
              v-model="form.enabled"
              :label="item.value"
            >
              {{ item.label }}
            </el-radio>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row>
        <el-col :span="24">
          <el-form-item
            label="实现路径"
          >
            <el-input
              v-model="form.implementationPath"
              placeholder="请输入算法实现文件路径"
              style="width: 500px;"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <!-- 替换现有的 ParameterViewer -->
      <el-row v-if="crud.status.edit > 0">
        <el-col :span="24">
          <el-form-item label="参数配置">
            <ParameterReadOnlyViewer
              :config-schema="form.configSchema"
              :default-config="form.defaultConfig"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <div v-if="crud.status.add > 0" class="parameter-config-section">
        <el-row>
          <el-col :span="24">
            <el-form-item label="参数配置">
              <ParameterConfigEditor
                :config-schema="form.configSchema"
                :default-config="form.defaultConfig"
                @config-change="handleParameterConfigChange"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </div>
    </el-form>

    <div
      slot="footer"
      class="dialog-footer"
    >
      <el-button
        type="text"
        @click="crud.cancelCU"
      >
        取消
      </el-button>
      <el-button
        :loading="crud.status.cu === 2"
        type="primary"
        @click="crud.submitCU"
      >
        确认
      </el-button>
    </div>
  </el-dialog>
</template>

<script>
import CRUD, { form } from '@crud/crud'
import ParameterConfigEditor from './parameterConfigEditor'
import ParameterReadOnlyViewer from './parameterReadOnlyViewer'

const defaultForm = {
  algorithmId: null,
  algorithmName: '',
  description: '',
  algorithmType: '',
  defenseStage: '',
  algorithmCategory: '',
  implementationPath: '',
  defaultConfig: '',
  configSchema: '',
  enabled: true
}

export default {
  components: { ParameterConfigEditor, ParameterReadOnlyViewer },
  mixins: [form(defaultForm)],
  data() {
    return {
      parameterConfigData: {
        configSchema: '',
        defaultConfig: ''
      },
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
      ],
      rules: {
        algorithmName: [
          { required: true, message: '请输入算法名称', trigger: 'blur' }
        ],
        algorithmType: [
          { required: true, message: '请选择算法类型', trigger: 'change' }
        ],
        defenseStage: [
          { required: true, message: '请选择防御阶段', trigger: 'change' }
        ]
      }
    }
  },
  methods: {
    // 处理参数配置变更
    handleParameterConfigChange(configData) {
      this.form.configSchema = configData.configSchema
      this.form.defaultConfig = configData.defaultConfig
    },

    // 修改现有的 [CRUD.HOOK.beforeValidateCU] 方法
    [CRUD.HOOK.beforeValidateCU]() {
      if (this.crud.status.cu === 1) {
        // 新增时不需要JSON验证了，因为动态表单已经保证格式正确
        return true
      }
      return true
    }
    // 验证JSON格式
    // validateJsonInForm() {
    //   // 验证默认配置JSON格式
    //   if (this.form.defaultConfig && this.form.defaultConfig.trim()) {
    //     try {
    //       JSON.parse(this.form.defaultConfig)
    //     } catch (e) {
    //       this.$message.error('默认配置不是有效的JSON格式')
    //       return false
    //     }
    //   }
    //
    //   // 验证配置模式JSON格式
    //   if (this.form.configSchema && this.form.configSchema.trim()) {
    //     try {
    //       JSON.parse(this.form.configSchema)
    //     } catch (e) {
    //       this.$message.error('配置模式不是有效的JSON格式')
    //       return false
    //     }
    //   }
    //
    //   return true
    // },
    //
    // // 手动验证JSON格式按钮
    // validateJsonFormat() {
    //   if (this.validateJsonInForm()) {
    //     this.$message.success('JSON格式验证通过')
    //   }
    // }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
.simple-config-section {
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 20px;
  background-color: #f8f9fa;
  margin-top: 10px;
}

::v-deep .parameter-config-editor {
    width: 100%;
    max-width: 900px;
}
.dialog-footer {
  text-align: right;
}

::v-deep .el-dialog__body {
  padding-bottom: 10px;
}
</style>
