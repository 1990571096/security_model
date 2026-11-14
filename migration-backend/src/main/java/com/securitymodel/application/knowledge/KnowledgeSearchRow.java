package com.securitymodel.application.knowledge;

import lombok.Data;

/** Joined chunk/document row used by retrieval; never returned raw to the client. */
@Data
public class KnowledgeSearchRow {
    private Long chunkId;
    private Long documentId;
    private Integer chunkIndex;
    private String sectionPath;
    private String content;
    private Integer startLine;
    private Integer endLine;
    private String title;
    private String sourceKey;
    private String sourceType;
    private Integer version;
    private String visibility;
    private Long ownerUserId;
}
