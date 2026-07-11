package me.zhengjie.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import me.zhengjie.modules.system.domain.Model;
import me.zhengjie.modules.system.domain.dto.ModelQueryCriteria;
import me.zhengjie.utils.PageResult;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 模型管理服务接口
 * @author ZJING
 * @date 2025-07-27
 */
public interface ModelService extends IService<Model> {

    /**
     * 根据ID查询模型
     * @param id 模型ID
     * @return 模型信息
     */
    Model findById(Long id);

    /**
     * 创建模型
     * @param resources 模型信息
     */
    void create(Model resources);

    /**
     * 更新模型
     * @param resources 模型信息
     */
    void update(Model resources);

    /**
     * 删除模型
     * @param ids 模型ID集合
     */
    void delete(Set<Long> ids);

    /**
     * 根据模型名称查询
     * @param modelName 模型名称
     * @return 模型信息
     */
    Model findByModelName(String modelName);

    /**
     * 分页查询模型
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    PageResult<Model> queryAll(ModelQueryCriteria criteria, Page<Object> page);

    /**
     * 查询所有模型（不分页）
     * @param criteria 查询条件
     * @return 模型列表
     */
    List<Model> queryAll(ModelQueryCriteria criteria);

    /**
     * 导出模型数据
     * @param queryAll 待导出的数据
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    void download(List<Model> queryAll, HttpServletResponse response) throws IOException;

    /**
     * 更新模型解析状态
     * @param modelId 模型ID
     * @param parseStatus 解析状态
     * @param parseMessage 解析消息
     */
    void updateParseStatus(Long modelId, String parseStatus, String parseMessage);

    /**
     * 更新模型状态
     * @param modelId 模型ID
     * @param status 模型状态
     */
    void updateStatus(Long modelId, String status);

    /**
     * 根据模型来源查询
     * @param modelSource 模型来源
     * @return 模型列表
     */
    List<Model> findByModelSource(String modelSource);

    /**
     * 根据创建人查询
     * @param createBy 创建人名字
     * @return 模型列表
     */
    List<Model> findByCreateBy(String createBy);
}