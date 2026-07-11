package me.zhengjie;

import lombok.extern.slf4j.Slf4j;
import me.zhengjie.AppRun;
import me.zhengjie.domain.GenConfig;
import me.zhengjie.domain.ColumnInfo;
import me.zhengjie.service.GeneratorService;
import me.zhengjie.service.GenConfigService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import java.util.List;

/**
 * 模型管理代码生成器
 * 在 eladmin-system 模块中运行
 */
@Slf4j
//@RunWith(SpringRunner.class)
//@SpringBootTest(
//        classes = AppRun.class,
//        webEnvironment = SpringBootTest.WebEnvironment.NONE,
//        properties = {
//                "spring.websocket.enabled=false",
//                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
//        }
//)
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {
        GeneratorService.class,
        GenConfigService.class,
        // 添加其他必要的服务类，但排除 Security 和 Web 相关配置
})
@TestPropertySource(properties = {
        "spring.main.web-application-type=none",
        "logging.level.root=WARN"
})
public class ModelCodeGenerator {

    @Autowired
    private GeneratorService generatorService;

    @Autowired
    private GenConfigService genConfigService;
    public static void main(String[] args) {
        // 直接调用代码生成工具类
        generateSysModelCode();
    }

    public static void generateSysModelCode() {
        try {
            GenConfig genConfig = new GenConfig();
            genConfig.setTableName("sys_model");
            genConfig.setPack("me.zhengjie.modules.system");
            genConfig.setModuleName("eladmin-system");
            genConfig.setPrefix("sys_");
            genConfig.setApiAlias("模型管理");
            genConfig.setAuthor("YourName");
            genConfig.setCover(true);
            genConfig.setPath("src/views/system/model");
            genConfig.setApiPath("src/api/system");

            // 直接使用 GenUtil 生成代码（需要手动获取列信息）
            // 这个方法需要你手动从数据库获取列信息
            System.out.println("配置完成，需要手动获取数据库列信息");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 生成 sys_model 表的完整代码
     */
//    @Test
//    public void generateSysModelCode() {
//        try {
//            String tableName = "sys_model";
//            log.info("开始生成 {} 表的代码", tableName);
//
//            // 1. 创建或更新表配置
//            GenConfig genConfig = createGenConfig(tableName);
//            genConfigService.update(tableName, genConfig);
//            log.info("表配置更新完成");
//
//            // 2. 获取表的列信息
//            List<ColumnInfo> columnInfos = generatorService.getColumns(tableName);
//            if (columnInfos.isEmpty()) {
//                log.error("未找到表 {} 的列信息，请检查表是否存在", tableName);
//                return;
//            }
//            log.info("获取到 {} 个字段信息", columnInfos.size());
//
//            // 3. 同步表结构（从数据库获取最新的表结构）
//            List<ColumnInfo> dbColumns = generatorService.query(tableName);
//            generatorService.sync(columnInfos, dbColumns);
//            log.info("表结构同步完成");
//
//            // 4. 重新获取同步后的列信息
//            columnInfos = generatorService.getColumns(tableName);
//
//            // 5. 生成代码
//            generatorService.generator(genConfig, columnInfos);
//
//            log.info("✅ {} 表代码生成成功！", tableName);
//            System.out.println("生成的文件包括：");
//            System.out.println("- SysModel.java (实体类)");
//            System.out.println("- SysModelController.java (控制器)");
//            System.out.println("- SysModelService.java (服务接口)");
//            System.out.println("- SysModelServiceImpl.java (服务实现)");
//            System.out.println("- SysModelQueryCriteria.java (查询条件)");
//            System.out.println("- SysModelMapper.java (Mapper接口)");
//            System.out.println("- SysModelMapper.xml (MyBatis映射文件)");
//
//        } catch (Exception e) {
//            log.error("❌ sys_model 代码生成失败", e);
//        }
//    }

    /**
     * 生成 sys_model_log 表的完整代码
     */
    @Test
    public void generateSysModelLogCode() {
        try {
            String tableName = "sys_model_log";
            log.info("开始生成 {} 表的代码", tableName);

            // 1. 创建或更新表配置
            GenConfig genConfig = createGenConfigForLog(tableName);
            genConfigService.update(tableName, genConfig);

            // 2. 获取表的列信息
            List<ColumnInfo> columnInfos = generatorService.getColumns(tableName);
            if (columnInfos.isEmpty()) {
                log.error("未找到表 {} 的列信息，请检查表是否存在", tableName);
                return;
            }

            // 3. 同步表结构
            List<ColumnInfo> dbColumns = generatorService.query(tableName);
            generatorService.sync(columnInfos, dbColumns);

            // 4. 重新获取同步后的列信息
            columnInfos = generatorService.getColumns(tableName);

            // 5. 生成代码
            generatorService.generator(genConfig, columnInfos);

            log.info("✅ {} 表代码生成成功！", tableName);

        } catch (Exception e) {
            log.error("❌ {} 代码生成失败");
        }
    }

    /**
     * 一次性生成两个表的代码
     */
    @Test
    public void generateAllModelCode() {
        log.info("🚀 开始生成模型管理相关代码...");
        generateSysModelCode();
        generateSysModelLogCode();
        log.info("🎉 所有代码生成完成！");
    }

    /**
     * 创建 sys_model 表的生成配置
     */
    private GenConfig createGenConfig(String tableName) {
        GenConfig genConfig = new GenConfig();
        genConfig.setTableName(tableName);
        genConfig.setPack("me.zhengjie.modules.system");  // 包名
        genConfig.setModuleName("eladmin-system");        // 模块名（对应项目目录）
        genConfig.setPrefix("sys_");                      // 表前缀
        genConfig.setApiAlias("模型管理");                  // 接口别名
        genConfig.setAuthor("YourName");                  // 作者
        genConfig.setCover(true);                         // 是否覆盖现有文件

        // 前端相关路径
        genConfig.setPath("src/views/system/model");      // 前端页面路径
        genConfig.setApiPath("src/api/system");           // 前端API路径

        return genConfig;
    }

    /**
     * 创建 sys_model_log 表的生成配置
     */
    private GenConfig createGenConfigForLog(String tableName) {
        GenConfig genConfig = new GenConfig();
        genConfig.setTableName(tableName);
        genConfig.setPack("me.zhengjie.modules.system");
        genConfig.setModuleName("eladmin-system");
        genConfig.setPrefix("sys_");
        genConfig.setApiAlias("模型日志");
        genConfig.setAuthor("YourName");
        genConfig.setCover(true);

        // 前端相关路径
        genConfig.setPath("src/views/system/model-log");
        genConfig.setApiPath("src/api/system");

        return genConfig;
    }

    /**
     * 预览生成的代码（不实际生成文件）
     */
    @Test
    public void previewModelCode() {
        try {
            String tableName = "sys_model";
            GenConfig genConfig = createGenConfig(tableName);
            List<ColumnInfo> columnInfos = generatorService.getColumns(tableName);

            if (columnInfos.isEmpty()) {
                log.error("未找到表的列信息");
                return;
            }

            // 预览代码
            ResponseEntity<Object> response = generatorService.preview(genConfig, columnInfos);
            log.info("📋 代码预览生成成功，请查看返回结果");

        } catch (Exception e) {
            log.error("代码预览失败", e);
        }
    }
}