<template>
  <div class="app-container">
    <el-row :gutter="20">
      <el-col :xs="24" :sm="24" :md="8" :lg="6" :xl="5" style="margin-bottom: 10px">
        <el-card class="box-card">
          <div slot="header" class="clearfix">
            <span>个人信息</span>
          </div>
          <div>
            <div style="text-align: center">
              <div class="el-upload">
                <img :src="user.avatarName ? baseApi + '/avatar/' + user.avatarName : Avatar" title="点击上传头像" class="avatar" @click="toggleShow">
                <myUpload
                  v-model="show"
                  :headers="headers"
                  :url="updateAvatarApi"
                  @crop-upload-success="cropUploadSuccess"
                />
              </div>
            </div>
            <ul class="user-info">
              <li><div style="height: 100%"><svg-icon icon-class="login" /> 登录账号<div class="user-right">{{ user.username }}</div></div></li>
              <li><svg-icon icon-class="user1" /> 用户昵称 <div class="user-right">{{ user.nickName }}</div></li>
<!--              <li><svg-icon icon-class="dept" /> 所属部门 <div class="user-right"> {{ user.dept.name }}</div></li>-->
              <li><svg-icon icon-class="phone" /> 手机号码 <div class="user-right">{{ user.phone }}</div></li>
              <li><svg-icon icon-class="email" /> 用户邮箱 <div class="user-right">{{ user.email }}</div></li>
              <li>
                <svg-icon icon-class="anq" /> 安全设置
                <div class="user-right">
                  <a @click="$refs.pass.dialog = true">修改密码</a>
                  <a @click="$refs.email.dialog = true">修改邮箱</a>
                </div>
              </li>
            </ul>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="16" :lg="18" :xl="19">
        <!--    用户资料    -->
        <el-card class="box-card">
          <el-tabs v-model="activeName" @tab-click="handleClick">
            <el-tab-pane label="用户资料" name="first">
              <el-form ref="form" :model="form" :rules="rules" style="margin-top: 10px;" size="small" label-width="65px">
                <el-form-item label="昵称" prop="nickName">
                  <el-input v-model="form.nickName" style="width: 35%" />
                  <span style="color: #C0C0C0;margin-left: 10px;">用户昵称不作为登录使用</span>
                </el-form-item>
                <el-form-item label="手机号" prop="phone">
                  <el-input v-model="form.phone" style="width: 35%;" />
                  <span style="color: #C0C0C0;margin-left: 10px;">手机号码不能重复</span>
                </el-form-item>
                <el-form-item label="性别">
                  <el-radio-group v-model="form.gender" style="width: 178px">
                    <el-radio label="男">男</el-radio>
                    <el-radio label="女">女</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="">
                  <el-button :loading="saveLoading" size="mini" type="primary" @click="doSubmit">保存配置</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>
            <!--    操作日志    -->
            <el-tab-pane label="操作日志" name="second">
              <el-table v-loading="loading" :data="data" style="width: 100%;">
                <el-table-column prop="description" label="行为" />
                <el-table-column prop="requestIp" label="IP" />
                <el-table-column :show-overflow-tooltip="true" prop="address" label="IP来源" />
                <el-table-column prop="browser" label="浏览器" />
                <el-table-column prop="time" label="请求耗时" align="center">
                  <template slot-scope="scope">
                    <el-tag v-if="scope.row.time <= 300">{{ scope.row.time }}ms</el-tag>
                    <el-tag v-else-if="scope.row.time <= 1000" type="warning">{{ scope.row.time }}ms</el-tag>
                    <el-tag v-else type="danger">{{ scope.row.time }}ms</el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  align="right"
                >
                  <template slot="header">
                    <div style="display:inline-block;float: right;cursor: pointer" @click="init">创建日期<i class="el-icon-refresh" style="margin-left: 40px" /></div>
                  </template>
                  <template slot-scope="scope">
                    <span>{{ scope.row.createTime }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <!--分页组件-->
              <el-pagination
                :total="total"
                :current-page="page"
                style="margin-top: 8px;"
                layout="total, prev, pager, next, sizes"
                @size-change="sizeChange"
                @current-change="pageChange"
              />
            </el-tab-pane>

            <!--    上传数据集    -->
            <el-tab-pane label="上传数据集" name="third">
              <el-table v-loading="datasetsLoading" :data="userDatasets" style="width: 100%;">
                <el-table-column prop="datasetName" label="数据集名称" />
                <el-table-column prop="datasetType" label="数据集类型" />
                <el-table-column prop="fileName" label="文件名" />
                <el-table-column prop="fileSize" label="文件大小">
                  <template slot-scope="scope">
                    {{ formatFileSize(scope.row.fileSize) }}
                  </template>
                </el-table-column>
                <el-table-column prop="parseStatus" label="解析状态">
                  <template slot-scope="scope">
                    <el-tag :type="getParseStatusType(scope.row.parseStatus)" size="small">
                      {{ formatParseStatus(scope.row.parseStatus) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="createTime" label="上传时间" />
              </el-table>
            </el-tab-pane>

            <!--    上传模型    -->
            <el-tab-pane label="上传模型" name="fourth">
              <el-table v-loading="modelsLoading" :data="userModels" style="width: 100%;">
                <el-table-column prop="modelName" label="模型名称" />
                <el-table-column prop="modelType" label="模型类型" />
                <el-table-column prop="modelSource" label="模型来源" />
                <el-table-column prop="parametersCount" label="参数数量" />
                <el-table-column prop="parseStatus" label="解析状态">
                  <template slot-scope="scope">
                    <el-tag :type="getParseStatusType(scope.row.parseStatus)" size="small">
                      {{ formatParseStatus(scope.row.parseStatus) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="createTime" label="上传时间" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>
    <updateEmail ref="email" :email="user.email" />
    <updatePass ref="pass" />
  </div>
</template>

<script>
import myUpload from 'vue-image-crop-upload'
import { mapGetters } from 'vuex'
import updatePass from './center/updatePass'
import updateEmail from './center/updateEmail'
import { getToken } from '@/utils/auth'
import store from '@/store'
import { isvalidPhone } from '@/utils/validate'
import crud from '@/mixins/crud'
import { editUser } from '@/api/system/user'
import Avatar from '@/assets/images/avatar.png'
import { getDatasetsByCreator } from '@/api/system/dataset'
import { getModelsByCreator } from '@/api/system/model'

export default {
  name: 'Center',
  components: { updatePass, updateEmail, myUpload },
  mixins: [crud],
  data() {
    // 自定义验证
    const validPhone = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请输入电话号码'))
      } else if (!isvalidPhone(value)) {
        callback(new Error('请输入正确的11位手机号码'))
      } else {
        callback()
      }
    }
    return {
      show: false,
      Avatar: Avatar,
      activeName: 'first',
      saveLoading: false,
      headers: {
        'Authorization': getToken()
      },
      userDatasets: [],
      userModels: [],
      datasetsLoading: false,
      modelsLoading: false,
      form: {},
      rules: {
        nickName: [
          { required: true, message: '请输入用户昵称', trigger: 'blur' },
          { min: 2, max: 20, message: '长度在 2 到 20 个字符', trigger: 'blur' }
        ],
        phone: [
          { required: true, trigger: 'blur', validator: validPhone }
        ]
      }
    }
  },
  computed: {
    ...mapGetters([
      'user',
      'updateAvatarApi',
      'baseApi'
    ])
  },
  created() {
    this.form = { id: this.user.id, nickName: this.user.nickName, gender: this.user.gender, phone: this.user.phone }
    store.dispatch('GetInfo').then(() => {})
  },
  methods: {
    toggleShow() {
      this.show = !this.show
    },
    handleClick(tab, event) {
      if (tab.name === 'second') {
        this.init()
      } else if (tab.name === 'third') {
        this.loadUserDatasets()
      } else if (tab.name === 'fourth') {
        this.loadUserModels()
      }
    },
    beforeInit() {
      this.url = 'api/logs/user'
      return true
    },
    cropUploadSuccess(jsonData, field) {
      store.dispatch('GetInfo').then(() => {
      })
    },
    // 加载用户数据集
    loadUserDatasets() {
      this.datasetsLoading = true
      getDatasetsByCreator(this.user.username).then(res => {
        this.userDatasets = res.data || res
      }).catch(err => {
        console.error('加载用户数据集失败:', err)
      }).finally(() => {
        this.datasetsLoading = false
      })
    },
    // 加载用户模型
    loadUserModels() {
      this.modelsLoading = true
      getModelsByCreator(this.user.username).then(res => {
        this.userModels = res.data || res
      }).catch(err => {
        console.error('加载用户模型失败:', err)
      }).finally(() => {
        this.modelsLoading = false
      })
    },

    // 格式化文件大小
    formatFileSize(bytes) {
      if (!bytes) return '0 B'
      const k = 1024
      const sizes = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
    },

    // 格式化解析状态
    formatParseStatus(status) {
      const statusMap = {
        'pending': '待解析',
        'parsing': '解析中',
        'success': '解析成功',
        'failed': '解析失败'
      }
      return statusMap[status] || status
    },
    // 获取解析状态标签类型
    getParseStatusType(status) {
      const typeMap = {
        'pending': 'info',
        'parsing': 'warning',
        'success': 'success',
        'failed': 'danger'
      }
      return typeMap[status] || 'info'
    },
    doSubmit() {
      if (this.$refs['form']) {
        this.$refs['form'].validate((valid) => {
          if (valid) {
            this.saveLoading = true
            editUser(this.form).then(() => {
              this.editSuccessNotify()
              store.dispatch('GetInfo').then(() => {
              })
              this.saveLoading = false
            }).catch(() => {
              this.saveLoading = false
            })
          }
        })
      }
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss">
  .avatar {
    width: 120px;
    height: 120px;
    border-radius: 50%;
  }
  .user-info {
    padding-left: 0;
    list-style: none;
    li{
      border-bottom: 1px solid #F0F3F4;
      padding: 11px 0;
      font-size: 13px;
    }
    .user-right {
      float: right;
      a{
        color: #317EF3;
      }
    }
  }
</style>
