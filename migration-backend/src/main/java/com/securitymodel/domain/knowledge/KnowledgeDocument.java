package com.securitymodel.domain.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One immutable version of a knowledge source; only the active version is searchable. */
@Data
@TableName("sys_knowledge_document")
public class KnowledgeDocument {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_RETIRED = "retired";
    public static final String VISIBILITY_SHARED = "shared";
    public static final String VISIBILITY_PRIVATE = "private";

    public static final String SOURCE_SDD = "sdd";
    public static final String SOURCE_ALGORITHM = "algorithm";
    public static final String SOURCE_TASK_REPORT = "task_report";

    @TableId(value = "document_id", type = IdType.AUTO)
    private Long documentId;
    private String sourceType;
    private String sourceKey;
    private Integer version;
    private String title;
    private String visibility;
    private Long ownerUserId;
    private String contentHash;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
