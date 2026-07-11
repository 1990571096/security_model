<template>
  <el-dialog
    append-to-body
    :close-on-click-modal="false"
    :before-close="crud.cancelCU"
    :visible="crud.status.cu > 0"
    :title="crud.status.title"
    width="600px"
  >
    <el-form
      ref="form"
      :model="form"
      :rules="rules"
      size="small"
      label-width="100px"
    >
      <el-form-item
        label="模型名称"
        prop="modelName"
      >
        <el-input
          v-model="form.modelName"
          placeholder="请输入模型名称"
          style="width: 450px;"
        />
      </el-form-item>

      <el-form-item
        label="模型描述"
        prop="description"
      >
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="请输入模型描述"
          style="width: 450px;"
        />
      </el-form-item>

      <el-form-item
        label="模型来源"
        prop="modelSource"
      >
        <el-select
          v-model="form.modelSource"
          placeholder="请选择模型来源"
          style="width: 450px;"
        >
          <el-option label="内置模型" value="builtin" />
          <el-option label="用户自定义" value="custom" />
        </el-select>
      </el-form-item>

      <el-form-item
        label="模型类型"
        prop="modelType"
      >
        <el-select
          v-model="form.modelType"
          placeholder="请选择模型类型"
          style="width: 450px;"
        >
          <el-option label="CNN" value="cnn" />
          <el-option label="RNN" value="rnn" />
          <el-option label="Transformer" value="transformer" />
          <el-option label="Linear" value="linear" />
          <el-option label="其他" value="other" />
        </el-select>
      </el-form-item>

      <el-form-item
        label="模型类别"
        prop="modelCategory"
      >
        <el-select
          v-model="form.modelCategory"
          placeholder="请选择模型类别"
          style="width: 450px;"
          clearable
        >
          <el-option label="图像分类" value="image_classification" />
          <el-option label="文本分类" value="text_classification" />
          <el-option label="回归" value="regression" />
          <el-option label="目标检测" value="object_detection" />
          <el-option label="语义分割" value="semantic_segmentation" />
          <el-option label="其他" value="other" />
        </el-select>
      </el-form-item>

      <el-form-item
        label="输入形状"
        prop="inputShape"
      >
        <el-input
          v-model="form.inputShape"
          placeholder="例如: (224, 224, 3)"
          style="width: 450px;"
        />
      </el-form-item>

      <el-form-item
        label="输出形状"
        prop="outputShape"
      >
        <el-input
          v-model="form.outputShape"
          placeholder="例如: (1000,)"
          style="width: 450px;"
        />
      </el-form-item>

      <el-form-item
        label="参数数量"
        prop="parametersCount"
      >
        <el-input-number
          v-model.number="form.parametersCount"
          :min="0"
          controls-position="right"
          placeholder="参数数量"
          style="width: 450px;"
        />
      </el-form-item>

      <el-form-item
        v-if="form.modelSource === 'custom'"
        label="Python文件"
        prop="pythonFile"
      >
        <el-upload
          ref="pythonUpload"
          action="#"
          :auto-upload="false"
          :on-change="handlePythonFileChange"
          :before-remove="handlePythonFileRemove"
          :file-list="pythonFileList"
          accept=".py"
          style="width: 450px;"
        >
          <el-button size="small" type="primary">选择文件</el-button>
          <div slot="tip" class="el-upload__tip">只能上传.py文件，且不超过10MB</div>
        </el-upload>
      </el-form-item>

      <el-form-item
        label="模型文件"
        prop="modelFile"
      >
        <el-upload
          ref="modelUpload"
          action="#"
          :auto-upload="false"
          :on-change="handleModelFileChange"
          :before-remove="handleModelFileRemove"
          :file-list="modelFileList"
          accept=".pkl,.pth,.h5,.onnx"
          style="width: 450px;"
        >
          <el-button size="small" type="primary">选择文件</el-button>
          <div slot="tip" class="el-upload__tip">支持.pkl, .pth, .h5, .onnx格式，且不超过100MB</div>
        </el-upload>
      </el-form-item>

      <el-form-item
        label="模型状态"
        prop="status"
      >
        <el-select
          v-model="form.status"
          placeholder="请选择模型状态"
          style="width: 450px;"
        >
          <el-option label="草稿" value="draft" />
          <el-option label="可用" value="available" />
          <el-option label="维护中" value="maintenance" />
          <el-option label="已弃用" value="deprecated" />
        </el-select>
      </el-form-item>
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
import { form } from '@crud/crud'
import CRUD from '@crud/crud'

const defaultForm = {
  modelId: null,
  modelName: '',
  description: '',
  modelSource: 'builtin',
  modelType: 'cnn',
  modelCategory: '',
  architecture: '',
  inputShape: '',
  outputShape: '',
  parametersCount: null,
  pythonFilePath: '',
  pythonFileName: '',
  pythonFileSize: null,
  modelFilePath: '',
  modelConfig: '',
  parseStatus: 'pending',
  parseMessage: '',
  parseTime: null,
  status: 'draft',
  createBy: null,
  updateBy: null,
  isDeleted: false
}

export default {
  mixins: [form(defaultForm)],
  data() {
    return {
      pythonFileList: [],
      modelFileList: [],
      rules: {
        modelName: [
          { required: true, message: '请输入模型名称', trigger: 'blur' },
          { min: 2, max: 100, message: '长度在 2 到 100 个字符', trigger: 'blur' }
        ],
        modelSource: [
          { required: true, message: '请选择模型来源', trigger: 'change' }
        ],
        modelType: [
          { required: true, message: '请选择模型类型', trigger: 'change' }
        ],
        status: [
          { required: true, message: '请选择模型状态', trigger: 'change' }
        ],
        parametersCount: [
          { type: 'number', message: '参数数量必须为数字', trigger: 'blur' }
        ]
      }
    }
  },
  methods: {
    // 表单提交前的数据处理
    [CRUD.HOOK.beforeSubmit]() {
      // 确保数字类型字段的正确性
      if (this.form.parametersCount === '') {
        this.form.parametersCount = null
      }

      // 清理空字符串字段
      Object.keys(this.form).forEach(key => {
        if (this.form[key] === '') {
          this.form[key] = null
        }
      })

      return true
    },

    // 处理Python文件变化
    handlePythonFileChange(file, fileList) {
      if (file.size > 10 * 1024 * 1024) {
        this.$message.error('Python文件大小不能超过10MB')
        this.pythonFileList = fileList.filter(f => f.uid !== file.uid)
        return
      }
      this.pythonFileList = fileList
      this.form.pythonFileName = file.name
      this.form.pythonFileSize = file.size
    },

    // 处理Python文件移除
    handlePythonFileRemove(file, fileList) {
      this.pythonFileList = fileList
      this.form.pythonFileName = ''
      this.form.pythonFileSize = null
    },

    // 处理模型文件变化
    handleModelFileChange(file, fileList) {
      if (file.size > 100 * 1024 * 1024) {
        this.$message.error('模型文件大小不能超过100MB')
        this.modelFileList = fileList.filter(f => f.uid !== file.uid)
        return
      }
      this.modelFileList = fileList
    },

    // 处理模型文件移除
    handleModelFileRemove(file, fileList) {
      this.modelFileList = fileList
    }
  },
  watch: {
    // 监听模型来源变化，清空Python文件
    'form.modelSource'(newVal) {
      if (newVal !== 'custom') {
        this.pythonFileList = []
        this.form.pythonFileName = ''
        this.form.pythonFileSize = null
      }
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
::v-deep .el-input-number .el-input__inner {
  text-align: left;
}

::v-deep .el-upload__tip {
  font-size: 12px;
  color: #606266;
  margin-top: 7px;
}
</style>
