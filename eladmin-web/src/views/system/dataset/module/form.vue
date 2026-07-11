<template>
  <el-dialog
    append-to-body
    :close-on-click-modal="false"
    :before-close="handleClose"
    :visible="visible"
    title="上传数据集"
    width="600px"
  >
    <el-form
      ref="uploadForm"
      :model="form"
      :rules="rules"
      size="small"
      label-width="100px"
    >
      <el-form-item label="数据集名称" prop="datasetName">
        <el-input
          v-model="form.datasetName"
          placeholder="请输入数据集名称"
          style="width: 450px;"
        />
      </el-form-item>

      <el-form-item label="数据集描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          placeholder="请输入数据集描述"
          style="width: 450px;"
        />
      </el-form-item>

      <el-form-item label="数据集类型" prop="datasetType">
        <el-select
          v-model="form.datasetType"
          placeholder="请选择数据集类型"
          style="width: 450px;"
        >
          <el-option
            v-for="item in datasetTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="数据集文件" prop="file">
        <el-upload
          ref="upload"
          class="upload-demo"
          action=""
          :on-change="handleFileChange"
          :on-remove="handleFileRemove"
          :before-upload="beforeUpload"
          :auto-upload="false"
          :show-file-list="true"
          :limit="1"
          accept=".zip,.tar.gz,.rar"
        >
          <el-button size="small" type="primary" icon="el-icon-upload">选择文件</el-button>
          <div slot="tip" class="el-upload__tip">
            支持上传 .zip、.tar.gz、.rar 格式的数据集文件，文件大小不超过 500MB
          </div>
        </el-upload>
      </el-form-item>

      <el-form-item v-if="uploadProgress > 0" label="上传进度">
        <el-progress
          :percentage="uploadProgress"
          :status="uploadStatus"
          :stroke-width="8"
        />
      </el-form-item>
    </el-form>

    <div slot="footer" class="dialog-footer">
      <el-button type="text" @click="handleClose">取消</el-button>
      <el-button
        :loading="uploading"
        type="primary"
        @click="handleSubmit"
        :disabled="!form.file"
      >
        {{ uploading ? '上传中...' : '确认上传' }}
      </el-button>
    </div>
  </el-dialog>
</template>

<script>
import { uploadDataset } from '@/api/system/dataset'

export default {
  name: 'UploadForm',
  props: {
    visible: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      form: {
        datasetName: '',
        description: '',
        datasetType: '',
        file: null
      },
      datasetTypeOptions: [
        { value: 'image', label: '图像数据' },
        { value: 'text', label: '文本数据' },
        { value: 'tabular', label: '表格数据' }
      ],
      rules: {
        datasetName: [
          { required: true, message: '请输入数据集名称', trigger: 'blur' }
        ],
        datasetType: [
          { required: true, message: '请选择数据集类型', trigger: 'change' }
        ],
        file: [
          { required: true, message: '请选择数据集文件', trigger: 'change' }
        ]
      },
      uploading: false,
      uploadProgress: 0,
      uploadStatus: ''
    }
  },
  methods: {
    // 文件选择处理
    handleFileChange(file, fileList) {
      this.form.file = file.raw
      // 触发表单验证
      this.$nextTick(() => {
        this.$refs.uploadForm.validateField('file')
      })
    },

    // 文件移除处理
    handleFileRemove(file, fileList) {
      this.form.file = null
    },

    // 文件上传前验证
    beforeUpload(file) {
      // 检查文件类型
      const validTypes = ['.zip', '.tar.gz', '.rar']
      const isValidType = validTypes.some(type => file.name.toLowerCase().endsWith(type))

      if (!isValidType) {
        this.$message.error('只能上传 .zip、.tar.gz、.rar 格式的文件!')
        return false
      }

      // 检查文件大小 (500MB)
      const isLt500M = file.size / 1024 / 1024 < 500
      if (!isLt500M) {
        this.$message.error('上传文件大小不能超过 500MB!')
        return false
      }

      return false // 阻止自动上传，手动处理
    },

    // 提交表单
    handleSubmit() {
      this.$refs.uploadForm.validate((valid) => {
        if (valid) {
          this.uploadFile()
        } else {
          this.$message.error('请完善表单信息')
          return false
        }
      })
    },

    // 上传文件
    uploadFile() {
      const formData = new FormData()
      formData.append('file', this.form.file)
      formData.append('datasetName', this.form.datasetName)
      formData.append('description', this.form.description)
      formData.append('datasetType', this.form.datasetType)

      this.uploading = true
      this.uploadProgress = 0
      this.uploadStatus = ''

      // 模拟上传进度
      const progressTimer = setInterval(() => {
        if (this.uploadProgress < 90) {
          this.uploadProgress += Math.random() * 10
        }
      }, 200)

      uploadDataset(formData).then(response => {
        clearInterval(progressTimer)
        this.uploadProgress = 100
        this.uploadStatus = 'success'

        setTimeout(() => {
          this.$message.success('数据集上传成功!')
          this.$emit('success', response.data)
          this.handleClose()
        }, 500)
      }).catch(error => {
        clearInterval(progressTimer)
        this.uploadProgress = 0
        this.uploadStatus = 'exception'
        this.uploading = false

        // const message = error.response?.data?.message || error.message || '上传失败'
        // this.$message.error('上传失败: ' + message)
        // 修复错误
        const response = error.response
        const message = (response && response.data && response.data.message) || error.message || '上传失败'
        this.$message.error('上传失败: ' + message)
      }).finally(() => {
        setTimeout(() => {
          this.uploading = false
          this.uploadProgress = 0
          this.uploadStatus = ''
        }, 1000)
      })
    },

    // 关闭对话框
    handleClose() {
      // 重置表单
      this.resetForm()
      this.$emit('update:visible', false)
    },

    // 重置表单
    resetForm() {
      this.form = {
        datasetName: '',
        description: '',
        datasetType: '',
        file: null
      }
      this.uploadProgress = 0
      this.uploadStatus = ''
      this.uploading = false

      // 清空文件列表
      if (this.$refs.upload) {
        this.$refs.upload.clearFiles()
      }

      // 清空表单验证
      this.$nextTick(() => {
        if (this.$refs.uploadForm) {
          this.$refs.uploadForm.clearValidate()
        }
      })
    }
  }
}
</script>

<style scoped>
.upload-demo {
  width: 450px;
}

.el-upload__tip {
  color: #999;
  font-size: 12px;
  line-height: 1.5;
}
</style>
