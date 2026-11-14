package com.securitymodel.domain.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A stable, citation-addressable slice of a document version. */
@Data
@TableName("sys_knowledge_chunk")
public class KnowledgeChunk {

    @TableId(value = "chunk_id", type = IdType.AUTO)
    private Long chunkId;
    private Long documentId;
    private Integer chunkIndex;
    private String sectionPath;
    private String content;
    private Integer startLine;
    private Integer endLine;
    private LocalDateTime createdAt;
}
