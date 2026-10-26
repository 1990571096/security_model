package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.hyper.Hyper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HyperMapper extends BaseMapper<Hyper> {
}
