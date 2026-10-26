package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.algorithm.Algorithm;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlgorithmMapper extends BaseMapper<Algorithm> {
}
