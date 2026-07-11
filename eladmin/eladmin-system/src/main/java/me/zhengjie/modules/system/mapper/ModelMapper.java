package me.zhengjie.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import me.zhengjie.modules.system.domain.Model;
import me.zhengjie.modules.system.domain.dto.ModelQueryCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型管理 Mapper
 * @author ZJING
 * @date 2025-07-27
 */
@Mapper
public interface ModelMapper extends BaseMapper<Model> {

    /**
     * 查询模型总数
     * @param criteria 查询条件
     * @return 总数
     */
    Long countAll(@Param("criteria") ModelQueryCriteria criteria);

    /**
     * 查询所有模型
     * @param criteria 查询条件
     * @return 模型列表
     */
    List<Model> findAll(@Param("criteria") ModelQueryCriteria criteria);

    /**
     * 分页查询模型
     * @param criteria 查询条件
     * @param page 分页参数
     * @return 分页结果
     */
    IPage<Model> findAll(@Param("criteria") ModelQueryCriteria criteria, Page<Object> page);

    /**
     * 根据模型名称查询
     * @param modelName 模型名称
     * @return 模型信息
     */
    Model findByModelName(@Param("modelName") String modelName);

    /**
     * 根据模型来源查询
     * @param modelSource 模型来源
     * @return 模型列表
     */
    List<Model> findByModelSource(@Param("modelSource") String modelSource);

    /**
     * 根据模型类型查询
     * @param modelType 模型类型
     * @return 模型列表
     */
    List<Model> findByModelType(@Param("modelType") String modelType);

    /**
     * 根据解析状态查询
     * @param parseStatus 解析状态
     * @return 模型列表
     */
    List<Model> findByParseStatus(@Param("parseStatus") String parseStatus);

    /**
     * 根据模型状态查询
     * @param status 模型状态
     * @return 模型列表
     */
    List<Model> findByStatus(@Param("status") String status);

    /**
     * 根据创建人查询
     * @param createBy 创建人名字
     * @return 模型列表
     */
    List<Model> findByCreateBy(@Param("createBy") String createBy);
}