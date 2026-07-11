package me.zhengjie.modules.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import me.zhengjie.modules.system.domain.Algorithm;
import me.zhengjie.modules.system.domain.dto.AlgorithmQueryCriteria;
import me.zhengjie.utils.PageResult;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 算法管理服务接口
 * @author ZJING
 * @date 2025-07-30
 */
public interface AlgorithmService extends IService<Algorithm> {

    /**
     * 根据ID查询算法
     * @param algorithmId 算法ID
     * @return 算法信息
     */
    Algorithm findById(Long algorithmId);

    /**
     * 创建算法
     * @param resources 算法信息
     */
    void create(Algorithm resources);

    /**
     * 更新算法
     * @param resources 算法信息
     */
    void update(Algorithm resources);

    /**
     * 删除算法
     * @param algorithmIds 算法ID集合
     */
    void delete(Set<Long> algorithmIds);

    /**
     * 根据算法名称查询
     * @param algorithmName 算法名称
     * @return 算法信息
     */
    Algorithm findByAlgorithmName(String algorithmName);

    /**
     * 分页查询算法
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    PageResult<Algorithm> queryAll(AlgorithmQueryCriteria criteria, Page<Object> page);

    /**
     * 查询所有算法（不分页）
     * @param criteria 查询条件
     * @return 算法列表
     */
    List<Algorithm> queryAll(AlgorithmQueryCriteria criteria);

    /**
     * 导出算法数据
     * @param algorithms 待导出的数据
     * @param response HTTP响应
     * @throws IOException IO异常
     */
    void download(List<Algorithm> algorithms, HttpServletResponse response) throws IOException;

    /**
     * 根据算法类型查询
     * @param algorithmType 算法类型
     * @return 算法列表
     */
    List<Algorithm> findByAlgorithmType(String algorithmType);

    /**
     * 根据防御阶段查询
     * @param defenseStage 防御阶段
     * @return 算法列表
     */
    List<Algorithm> findByDefenseStage(String defenseStage);

    /**
     * 根据算法分类查询
     * @param algorithmCategory 算法分类
     * @return 算法列表
     */
    List<Algorithm> findByAlgorithmCategory(String algorithmCategory);

    /**
     * 根据启用状态查询
     * @param enabled 启用状态
     * @return 算法列表
     */
    List<Algorithm> findByEnabled(Boolean enabled);

    /**
     * 获取可用算法（启用状态）
     * @param defenseStage 防御阶段（可选）
     * @param algorithmType 算法类型（可选）
     * @return 可用算法列表
     */
    List<Algorithm> findAvailableAlgorithms(String defenseStage, String algorithmType);

    /**
     * 更新默认配置
     * @param algorithmId 算法ID
     * @param defaultConfig 默认配置
     */
    void updateDefaultConfig(Long algorithmId, String defaultConfig);

    /**
     * 更新配置模式
     * @param algorithmId 算法ID
     * @param configSchema 配置模式
     */
    void updateConfigSchema(Long algorithmId, String configSchema);

    /**
     * 获取算法统计信息
     * @return 统计信息
     */
    java.util.Map<String, Object> getAlgorithmStatistics();

    /**
     * 验证算法参数
     * @param resources 算法信息
     */
    void validateAlgorithm(Algorithm resources);

    /**
     * 根据创建人查询
     * @param createBy 创建人
     * @return 算法列表
     */
    List<Algorithm> findByCreateBy(String createBy);
}