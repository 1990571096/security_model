<template>
  <div class="system-monitor">
    <!-- 系统信息卡片 -->
    <el-card class="info-card" v-if="data.sys">
      <div class="system-info">
        <svg-icon icon-class="system" style="margin-right: 5px" />
        <span>系统：{{ data.sys.os }}</span>
        <span>IP：{{ data.sys.ip }}</span>
        <span>运行：{{ data.sys.day }}</span>
        <i class="el-icon-refresh refresh-btn" @click="refreshData" />
      </div>
    </el-card>

    <!-- 监控数据区域 -->
    <el-card class="monitor-card">
      <div class="monitor-content">
        <!-- 左侧：状态圆形图 -->
        <div class="status-section">
          <div class="status-title">系统状态</div>
          <div class="status-grid">
            <!-- CPU使用率 -->
            <div class="status-item">
              <div class="status-label">CPU使用率</div>
              <el-tooltip placement="top">
                <div slot="content" style="font-size: 12px;">
                  <div>{{ data.cpu ? data.cpu.name : '' }}</div>
                  <div>{{ data.cpu ? data.cpu.package : '' }}</div>
                  <div>{{ data.cpu ? data.cpu.core : '' }}</div>
                  <div>{{ data.cpu ? data.cpu.logic : '' }}</div>
                </div>
                <el-progress
                  type="circle"
                  :width="80"
                  :percentage="parseFloat(data.cpu ? data.cpu.used : 0)"
                />
              </el-tooltip>
              <div class="status-footer">{{ data.cpu ? data.cpu.coreNumber : 0 }} 核心</div>
            </div>

            <!-- 内存使用率 -->
            <div class="status-item">
              <div class="status-label">内存使用率</div>
              <el-tooltip placement="top">
                <div slot="content" style="font-size: 12px;">
                  <div>总量：{{ data.memory ? data.memory.total : '' }}</div>
                  <div>已使用：{{ data.memory ? data.memory.used : '' }}</div>
                  <div>空闲：{{ data.memory ? data.memory.available : '' }}</div>
                </div>
                <el-progress
                  type="circle"
                  :width="80"
                  :percentage="parseFloat(data.memory ? data.memory.usageRate : 0)"
                />
              </el-tooltip>
              <div class="status-footer">{{ data.memory ? data.memory.used : '' }} / {{ data.memory ? data.memory.total : '' }}</div>
            </div>

            <!-- 交换区使用率 -->
            <div class="status-item">
              <div class="status-label">交换区使用率</div>
              <el-tooltip placement="top">
                <div slot="content" style="font-size: 12px;">
                  <div>总量：{{ data.swap ? data.swap.total : '' }}</div>
                  <div>已使用：{{ data.swap ? data.swap.used : '' }}</div>
                  <div>空闲：{{ data.swap ? data.swap.available : '' }}</div>
                </div>
                <el-progress
                  type="circle"
                  :width="80"
                  :percentage="parseFloat(data.swap ? data.swap.usageRate : 0)"
                />
              </el-tooltip>
              <div class="status-footer">{{ data.swap ? data.swap.used : '' }} / {{ data.swap ? data.swap.total : '' }}</div>
            </div>

            <!-- 磁盘使用率 -->
            <div class="status-item">
              <div class="status-label">磁盘使用率</div>
              <el-tooltip placement="top">
                <div slot="content" style="font-size: 12px;">
                  <div>总量：{{ data.disk ? data.disk.total : '' }}</div>
                  <div>空闲：{{ data.disk ? data.disk.available : '' }}</div>
                </div>
                <el-progress
                  type="circle"
                  :width="80"
                  :percentage="parseFloat(data.disk ? data.disk.usageRate : 0)"
                />
              </el-tooltip>
              <div class="status-footer">{{ data.disk ? data.disk.used : '' }} / {{ data.disk ? data.disk.total : '' }}</div>
            </div>
          </div>
        </div>

        <!-- 右侧：趋势图表 -->
        <div class="charts-section">
          <!-- CPU使用率趋势 -->
          <div class="chart-container">
            <div class="chart-title">CPU使用率监控</div>
            <v-chart :options="cpuChart" style="width: 400px; height: 300px;"/>
          </div>

          <!-- 内存使用率趋势 -->
          <div class="chart-container">
            <div class="chart-title">内存使用率监控</div>
            <v-chart :options="memoryChart" style="width: 400px; height: 300px;"/>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script>
import ECharts from 'vue-echarts'
import 'echarts/lib/chart/line'
import 'echarts/lib/component/polar'
import { initData } from '@/api/data'

export default {
  name: 'SystemMonitor',
  components: {
    'v-chart': ECharts
  },
  data() {
    return {
      data: {},
      monitor: null,
      cpuChart: {
        width: 350,
        height: 200,
        tooltip: {
          trigger: 'axis'
        },
        grid: {
          left: '3%',
          right: '4%',
          bottom: '3%',
          containLabel: true
        },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: []
        },
        yAxis: {
          type: 'value',
          min: 0,
          max: 100,
          interval: 20
        },
        series: [{
          data: [],
          type: 'line',
          smooth: true,
          areaStyle: {
            color: 'rgba(32, 160, 255, 0.2)'
          },
          itemStyle: {
            color: '#409EFF'
          }
        }]
      },
      memoryChart: {
        width: 350,
        height: 200,
        tooltip: {
          trigger: 'axis'
        },
        grid: {
          left: '3%',
          right: '4%',
          bottom: '3%',
          containLabel: true
        },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: []
        },
        yAxis: {
          type: 'value',
          min: 0,
          max: 100,
          interval: 20
        },
        series: [{
          data: [],
          type: 'line',
          smooth: true,
          areaStyle: {
            color: 'rgba(103, 194, 58, 0.2)'
          },
          itemStyle: {
            color: '#67C23A'
          }
        }]
      }
    }
  },
  mounted() {
    this.initMonitor()
  },
  destroyed() {
    if (this.monitor) {
      clearInterval(this.monitor)
    }
  },
  methods: {
    initMonitor() {
      this.loadData()
      // 每3.5秒刷新一次
      this.monitor = setInterval(() => {
        this.loadData()
      }, 3500)
    },
    loadData() {
      initData('api/monitor', {}).then(data => {
        this.data = data
        this.updateCharts(data)
      }).catch(error => {
        console.error('获取监控数据失败:', error)
      })
    },
    updateCharts(data) {
      // 最多保留8个数据点
      if (this.cpuChart.xAxis.data.length >= 8) {
        this.cpuChart.xAxis.data.shift()
        this.memoryChart.xAxis.data.shift()
        this.cpuChart.series[0].data.shift()
        this.memoryChart.series[0].data.shift()
      }

      // 添加新数据
      this.cpuChart.xAxis.data.push(data.time)
      this.memoryChart.xAxis.data.push(data.time)
      this.cpuChart.series[0].data.push(parseFloat(data.cpu ? data.cpu.used : 0))
      this.memoryChart.series[0].data.push(parseFloat(data.memory ? data.memory.usageRate : 0))
    },
    refreshData() {
      this.loadData()
      this.$message.success('数据刷新成功')
    }
  }
}
</script>

<style scoped>
.system-monitor {
  margin-bottom: 20px;
}

.info-card {
  margin-bottom: 10px;
}

.system-info {
  color: #666;
  font-size: 13px;
}

.system-info span {
  margin-right: 20px;
}

.refresh-btn {
  float: right;
  cursor: pointer;
  margin-left: 20px;
}

.refresh-btn:hover {
  color: #409EFF;
}

.monitor-content {
  display: flex;
  gap: 20px;
  height: 100%;
}

.status-section {
  flex: 0 0 280px;
  padding-right: 20px;
  border-right: 1px solid #EBEEF5;
}

.status-title {
  text-align: center;
  font-size: 16px;
  font-weight: 500;
  color: #666;
  margin-bottom: 20px;
  padding-bottom: 10px;
  border-bottom: 1px solid #EBEEF5;
}

.status-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.status-item {
  text-align: center;
}

.status-label {
  font-size: 12px;
  color: #999;
  margin-bottom: 8px;
}

.status-footer {
  font-size: 11px;
  color: #999;
  margin-top: 5px;
}

/* 在 systemMonitor.vue 的 <style scoped> 中修改 */
.charts-section {
  flex: 0.8;
  display: flex;
  flex-direction: row;  /* 改为 row */
  gap: 20px;
  height: 100%;
  max-width: 300px;
}

.chart-container {
  flex: 1;
  height: 100%;
  min-height: auto;  /* 移除固定的 min-height: 180px */
  display: flex;
  flex-direction: column;
}

.monitor-card {
  min-height: 320px;
}

.chart-title {
  font-size: 14px;
  font-weight: 500;
  color: #666;
  margin-bottom: 10px;
  padding-bottom: 5px;
  border-bottom: 1px solid #EBEEF5;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .monitor-content {
    flex-direction: column;
  }

  .status-section {
    flex: none;
    padding-right: 0;
    border-right: none;
    border-bottom: 1px solid #EBEEF5;
    padding-bottom: 20px;
    margin-bottom: 20px;
  }

  .status-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}

@media (max-width: 768px) {
  .status-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
