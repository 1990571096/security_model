package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import me.zhengjie.modules.system.domain.Hyper;
import me.zhengjie.modules.system.domain.dto.HyperQueryCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 超参数模板管理 Mapper
 * @author ZJING
 * @date 2025-08-02
 */
@Mapper
public interface HyperMapper extends BaseMapper<Hyper> {

    @Select("select template_id as templateId from sys_hyper where template_name = #{templateName}")
    Hyper findByTemplateName(@Param("templateName") String templateName);

    /**
     * 查询模板总数
     * @param criteria 查询条件
     * @return 总数
     */
    Long countAll(@Param("criteria") HyperQueryCriteria criteria);

    /**
     * 查询所有模板
     * @param criteria 查询条件
     * @return 模板列表
     */
    List<Hyper> findAll(@Param("criteria") HyperQueryCriteria criteria);

    /**
     * 分页查询模板
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<Hyper> findAll(@Param("criteria") HyperQueryCriteria criteria, Page<Object> page);

    /**
     * 根据算法ID查询
     * @param algorithmId 算法ID
     * @return 模板列表
     */
    List<Hyper> findByAlgorithmId(@Param("algorithmId") Long algorithmId);

    /**
     * 根据算法ID查询启用的模板
     * @param algorithmId 算法ID
     * @return 可用模板列表
     */
    List<Hyper> findEnabledByAlgorithmId(@Param("algorithmId") Long algorithmId);

    /**
     * 根据算法ID获取默认模板
     * @param algorithmId 算法ID
     * @return 默认模板
     */
    Hyper findDefaultByAlgorithmId(@Param("algorithmId") Long algorithmId);

    /**
     * 根据启用状态查询
     * @param enabled 启用状态
     * @return 模板列表
     */
    List<Hyper> findByEnabled(@Param("enabled") Boolean enabled);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 模板列表
     */
    List<Hyper> findByCreateBy(@Param("createBy") String createBy);

    /**
     * 根据适用数据集类型查询可用模板
     * @param algorithmId 算法ID
     * @param datasetType 数据集类型
     * @return 适用的模板列表
     */
    List<Hyper> findApplicableTemplates(@Param("algorithmId") Long algorithmId, 
                                       @Param("datasetType") String datasetType);

    /**
     * 更新默认状态（将其他模板设为非默认）
     * @param algorithmId 算法ID
     * @param excludeTemplateId 排除的模板ID
     */
    void updateDefaultStatus(@Param("algorithmId") Long algorithmId, 
                           @Param("excludeTemplateId") Long excludeTemplateId);

    /**
     * 获取算法的模板统计
     * @return 统计结果
     */
    List<java.util.Map<String, Object>> getTemplateStatistics();

    /**
     * 根据算法ID统计模板数量
     * @param algorithmId 算法ID
     * @return 模板数量
     */
    Integer countByAlgorithmId(@Param("algorithmId") Long algorithmId);
}