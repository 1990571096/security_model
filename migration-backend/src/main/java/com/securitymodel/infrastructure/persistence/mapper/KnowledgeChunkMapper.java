package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.knowledge.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
}
