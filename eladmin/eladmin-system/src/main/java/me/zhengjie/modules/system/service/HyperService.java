package me.zhengjie.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import me.zhengjie.modules.system.domain.Hyper;
import me.zhengjie.modules.system.domain.dto.HyperQueryCriteria;
import me.zhengjie.utils.PageResult;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 超参数模板管理服务接口
 * @author ZJING
 * @date 2025-08-02
 */
public interface HyperService extends IService<Hyper> {

    /**
     * 根据ID查询模板
     * @param templateId 模板ID
     * @return 模板信息
     */
    Hyper findById(Long templateId);

    /**
     * 创建模板
     * @param resources 模板信息
     */
    void create(Hyper resources);

    /**
     * 更新模板
     * @param resources 模板信息
     */
    void update(Hyper resources);

    /**
     * 删除模板
     * @param templateIds 模板ID集合
     */
    void delete(Set<Long> templateIds);

    /**
     * 根据模板名称查询
     * @param templateName 模板名称
     * @return 模板信息
     */
    Hyper findByTemplateName(String templateName);

    /**
     * 获取所有可用超参数模板（不分页）
     * @return 可用模板列表
     */
    List<Hyper> findAllAvailable();

    /**
     * 分页查询模板
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    PageResult<Hyper> queryAll(HyperQueryCriteria criteria, Page<Object> page);

    /**
     * 查询所有模板（不分页）
     * @param criteria 查询条件
     * @return 模板列表
     */
    List<Hyper> queryAll(HyperQueryCriteria criteria);

    /**
     * 导出模板数据
     * @param hypers 待导出的数据
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    void download(List<Hyper> hypers, HttpServletResponse response) throws IOException;

    /**
     * 根据算法ID查询模板
     * @param algorithmId 算法ID
     * @return 模板列表
     */
    List<Hyper> findByAlgorithmId(Long algorithmId);

    /**
     * 根据算法ID查询启用的模板
     * @param algorithmId 算法ID
     * @return 启用的模板列表
     */
    List<Hyper> findEnabledByAlgorithmId(Long algorithmId);

    /**
     * 根据算法ID获取默认模板
     * @param algorithmId 算法ID
     * @return 默认模板
     */
    Hyper findDefaultByAlgorithmId(Long algorithmId);

    /**
     * 根据启用状态查询
     * @param enabled 启用状态
     * @return 模板列表
     */
    List<Hyper> findByEnabled(Boolean enabled);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 模板列表
     */
    List<Hyper> findByCreateBy(String createBy);

    /**
     * 获取适用的模板
     * @param algorithmId 算法ID
     * @param datasetType 数据集类型（可选）
     * @return 适用的模板列表
     */
    List<Hyper> findApplicableTemplates(Long algorithmId, String datasetType);

    /**
     * 验证参数配置
     * @param templateId 模板ID
     * @param parameterConfig 参数配置
     * @return 验证结果
     */
    java.util.Map<String, Object> validateParameterConfig(Long templateId, String parameterConfig);

    /**
     * 合并参数配置
     * @param templateId 模板ID
     * @param customConfig 自定义配置
     * @return 合并后的配置
     */
    String mergeParameterConfig(Long templateId, String customConfig);

    /**
     * 获取模板统计信息
     * @return 统计信息
     */
    java.util.Map<String, Object> getTemplateStatistics();

    /**
     * 验证模板参数
     * @param resources 模板信息
     */
    void validateTemplate(Hyper resources);

    /**
     * 设置默认模板
     * @param templateId 模板ID
     */
    void setDefaultTemplate(Long templateId);

    /**
     * 检查模板名称是否重复
     * @param templateName 模板名称
     * @param excludeId 排除的模板ID（编辑时使用）
     * @return 是否重复
     */
    boolean isTemplateNameDuplicate(String templateName, Long excludeId);
}