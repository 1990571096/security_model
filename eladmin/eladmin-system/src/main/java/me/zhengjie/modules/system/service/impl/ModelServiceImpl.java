package me.zhengjie.modules.system.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson2.JSONFactory;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.security.service.dto.JwtUserDto;
import me.zhengjie.modules.system.domain.Model;
import me.zhengjie.modules.system.domain.dto.ModelQueryCriteria;
import me.zhengjie.modules.system.mapper.ModelMapper;
import me.zhengjie.modules.system.service.ModelService;
import me.zhengjie.utils.FileUtil;
import me.zhengjie.utils.PageResult;
import me.zhengjie.utils.PageUtil;
import me.zhengjie.utils.SecurityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import java.io.File;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 模型管理服务实现
 * @author ZJING
 * @date 2025-07-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl extends ServiceImpl<ModelMapper, Model> implements ModelService {

    private final ModelMapper modelMapper;

    @Override
    public Model findById(Long id) {
        Model model = modelMapper.selectById(id);
        if (ObjectUtil.isNull(model)) {
            throw new BadRequestException("模型不存在");
        }
        return model;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(Model resources) {
        // 检查模型名称是否已存在
        if (modelMapper.findByModelName(resources.getModelName()) != null) {
            throw new BadRequestException("模型名称已存在");
        }
        
        // 设置默认值
        if (ObjectUtil.isEmpty(resources.getParseStatus())) {
            resources.setParseStatus("pending");
        }
        if (ObjectUtil.isEmpty(resources.getStatus())) {
            resources.setStatus("draft");
        }
        
        modelMapper.insert(resources);
        log.info("用户 {} 创建了新模型: {} (ID: {})",
                SecurityUtils.getCurrentUsername(),
                resources.getModelName(),
                resources.getModelId());
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void update(Model resources) {
//        Model model = findById(resources.getModelId());
//
//        // 检查模型名称是否已被其他模型使用
//        Model modelByName = modelMapper.findByModelName(resources.getModelName());
//        if (modelByName != null && !modelByName.getModelId().equals(resources.getModelId())) {
//            throw new BadRequestException("模型名称已存在");
//        }
//
//        modelMapper.updateById(resources);
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Model resources) {
        Model model = findById(resources.getModelId());

        // 检查模型名称是否已被其他模型使用
        Model modelByName = modelMapper.findByModelName(resources.getModelName());
        if (modelByName != null && !modelByName.getModelId().equals(resources.getModelId())) {
            throw new BadRequestException("模型名称已存在");
        }

        // 设置正确的字段 - 使用Long类型的用户ID
        resources.setUpdateByLong(SecurityUtils.getCurrentUserId());

        modelMapper.updateById(resources);
    }

    private boolean isAdmin() {
        try {
            UserDetails userDetails = SecurityUtils.getCurrentUser();
            if (userDetails instanceof JwtUserDto) {
                JwtUserDto jwtUser = (JwtUserDto) userDetails;
                return jwtUser.getUser().getIsAdmin() != null && jwtUser.getUser().getIsAdmin();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void delete(Set<Long> ids) {
//        for (Long id : ids) {
//            Model model = findById(id);
//            // 可以添加删除前的业务检查
//            // 例如：如果模型正在使用中，不允许删除
//        }
//        modelMapper.deleteBatchIds(ids);
//    }

//    @Override
//    @Transactional(rollbackFor = Exception.class)
//    public void delete(Set<Long> ids) {
//        // 获取当前用户信息
//        Long currentUserId = SecurityUtils.getCurrentUserId();
//        boolean isAdmin = isAdmin(); // 使用新添加的方法
//
//        for (Long id : ids) {
//            System.out.println("modelId:"+id); //modelId:null，意思是没有从前端传id到后端呗
//            Model model = findById(id);
//
//            // 业务检查1：权限检查 - 只能删除自己创建的模型
//            if (!ObjectUtil.equal(model.getCreatedBy(), currentUserId)) {
//                throw new BadRequestException("只能删除自己创建的模型【" + model.getModelName() + "】");
//            }
//
//            // 业务检查2：内置模型只有管理员可以删除
//            if ("builtin".equals(model.getModelSource()) && !isAdmin) {
//                throw new BadRequestException("内置模型【" + model.getModelName() + "】只有管理员可以删除");
//            }
//
//            // 清理相关文件
//            cleanupModelFiles(model);
//        }
//
//        // 执行批量硬删除
//        modelMapper.deleteBatchIds(ids);
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Set<Long> ids) {
        // 添加ID集合空校验
        if (ids == null || ids.isEmpty()) {
            log.error("Model delete called with empty ID set");
            throw new BadRequestException("未提供要删除的模型ID");
        }

        // 获取当前用户信息
//        Long currentUserId = SecurityUtils.getCurrentUserId();
        String currentUser = SecurityUtils.getCurrentUsername();
        boolean isAdmin = isAdmin();

        // 遍历所有待删除的ID，保持原有的权限+文件清理逻辑
        for (Long id : ids) {
            if (id == null) {
                log.warn("Detected null ID in delete set: {}", ids);
                continue; // 跳过无效ID
            }

            Model model = findById(id); // 这里会检查模型是否存在
            // 权限检查逻辑
            if (!ObjectUtil.equal(model.getCreateBy(), currentUser)) {
                throw new BadRequestException("只能删除自己创建的模型【" + model.getModelName() + "】");
            }
            if ("builtin".equals(model.getModelSource()) && !isAdmin) {
                throw new BadRequestException("内置模型【" + model.getModelName() + "】只有管理员可以删除");
            }

            // 清理模型文件
            cleanupModelFiles(model);
        }

        // 执行实际的删除操作
        modelMapper.deleteBatchIds(ids);
    }


    /**
     * 清理模型相关文件
     *
     */
    private void cleanupModelFiles(Model model) {
        try {
            // 删除Python文件
            if (ObjectUtil.isNotEmpty(model.getPythonFilePath())) {
                File pythonFile = new File(model.getPythonFilePath());
                if (pythonFile.exists() && !pythonFile.delete()) {
                    log.warn("删除Python文件失败: {}", model.getPythonFilePath());
                }
            }

            // 删除模型文件
            if (ObjectUtil.isNotEmpty(model.getModelFilePath())) {
                File modelFile = new File(model.getModelFilePath());
                if (modelFile.exists() && !modelFile.delete()) {
                    log.warn("删除模型文件失败: {}", model.getModelFilePath());
                }
            }
        } catch (Exception e) {
            // 文件删除失败不影响数据库删除，只记录日志
            log.warn("删除模型文件时发生异常: {}", e.getMessage());
        }
    }

    @Override
    public Model findByModelName(String modelName) {
        return modelMapper.findByModelName(modelName);
    }

    @Override
    public PageResult<Model> queryAll(ModelQueryCriteria criteria, Page<Object> page) {
        criteria.setOffset(page.offset());
        IPage<Model> modelPage = modelMapper.findAll(criteria, page);
        return PageUtil.toPage(modelPage);
    }

    @Override
    public List<Model> queryAll(ModelQueryCriteria criteria) {
        return modelMapper.findAll(criteria);
    }

    @Override
    public void download(List<Model> queryAll, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Model model : queryAll) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("模型ID", model.getModelId());
            map.put("模型名称", model.getModelName());
            map.put("模型描述", model.getDescription());
            map.put("模型来源", model.getModelSource());
            map.put("模型类型", model.getModelType());
            map.put("模型类别", model.getModelCategory());
            map.put("输入形状", model.getInputShape());
            map.put("输出形状", model.getOutputShape());
            map.put("参数数量", model.getParametersCount());
            map.put("解析状态", model.getParseStatus());
            map.put("模型状态", model.getStatus());
            map.put("创建时间", model.getCreateTime());
            list.add(map);
        }
        FileUtil.downloadExcel(list, response);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateParseStatus(Long modelId, String parseStatus, String parseMessage) {
        Model model = findById(modelId);
        model.setParseStatus(parseStatus);
        model.setParseMessage(parseMessage);
        model.setParseTime(LocalDateTime.now());
        modelMapper.updateById(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long modelId, String status) {
        Model model = findById(modelId);
        model.setStatus(status);
        modelMapper.updateById(model);
    }

    @Override
    public List<Model> findByModelSource(String modelSource) {
        return modelMapper.findByModelSource(modelSource);
    }

    @Override
    public List<Model> findByCreateBy(String createBy) {
        return modelMapper.findByCreateBy(createBy);
    }
}