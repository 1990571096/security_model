package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.dataset.Dataset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {
    @Select("SELECT * FROM sys_dataset WHERE dataset_id = #{id} FOR UPDATE")
    Dataset lockForUpdate(Long id);
}
