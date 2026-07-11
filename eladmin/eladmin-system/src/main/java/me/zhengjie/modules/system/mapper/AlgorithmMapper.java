package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.dto.AlgorithmQueryCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 算法管理 Mapper
 * @author ZJING
 * @date 2025-07-30
 */
@Mapper
public interface AlgorithmMapper extends BaseMapper<Algorithm> {

    @Select("select algorithm_id as algorithmId from sys_algorithm where algorithm_name = #{algorithmName}")
    Algorithm findByAlgorithmName(@Param("algorithmName") String algorithmName);

    /**
     * 查询算法总数
     * @param criteria 查询条件
     * @return 总数
     */
    Long countAll(@Param("criteria") AlgorithmQueryCriteria criteria);

    /**
     * 查询所有算法
     * @param criteria 查询条件
     * @return 算法列表
     */
    List<Algorithm> findAll(@Param("criteria") AlgorithmQueryCriteria criteria);

    /**
     * 分页查询算法
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<Algorithm> findAll(@Param("criteria") AlgorithmQueryCriteria criteria, Page<Object> page);

    /**
     * 根据算法类型查询
     * @param algorithmType 算法类型
     * @return 算法列表
     */
    List<Algorithm> findByAlgorithmType(@Param("algorithmType") String algorithmType);

    /**
     * 根据防御阶段查询
     * @param defenseStage 防御阶段
     * @return 算法列表
     */
    List<Algorithm> findByDefenseStage(@Param("defenseStage") String defenseStage);

    /**
     * 根据算法分类查询
     * @param algorithmCategory 算法分类
     * @return 算法列表
     */
    List<Algorithm> findByAlgorithmCategory(@Param("algorithmCategory") String algorithmCategory);

    /**
     * 根据启用状态查询
     * @param enabled 启用状态
     * @return 算法列表
     */
    List<Algorithm> findByEnabled(@Param("enabled") Boolean enabled);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 算法列表
     */
    List<Algorithm> findByCreateBy(@Param("createBy") String createBy);

    /**
     * 获取可用算法（启用状态）
     * @param defenseStage 防御阶段（可选）
     * @param algorithmType 算法类型（可选）
     * @return 可用算法列表
     */
    List<Algorithm> findAvailableAlgorithms(@Param("defenseStage") String defenseStage, 
                                           @Param("algorithmType") String algorithmType);

    /**
     * 更新默认配置
     * @param algorithmId 算法ID
     * @param defaultConfig 默认配置
     */
    void updateDefaultConfig(@Param("algorithmId") Long algorithmId, 
                            @Param("defaultConfig") String defaultConfig);

    /**
     * 更新配置模式
     * @param algorithmId 算法ID
     * @param configSchema 配置模式
     */
    void updateConfigSchema(@Param("algorithmId") Long algorithmId, 
                           @Param("configSchema") String configSchema);

    /**
     * 获取防御阶段的算法数量统计
     * @return 统计结果
     */
    List<java.util.Map<String, Object>> getDefenseStageStatistics();

    /**
     * 获取算法类型的数量统计
     * @return 统计结果
     */
    List<java.util.Map<String, Object>> getAlgorithmTypeStatistics();
}