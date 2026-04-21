package com.wzh.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wzh.agentdemo.common.entity.FeatureDocument;
import com.wzh.entity.dto.FeatureDocumentDTO;
import com.wzh.entity.dto.PageRequest;

public interface FeatureDocumentService extends IService<FeatureDocument> {

    /**
     * 新增功能文档
     */
    Long addDocument(FeatureDocumentDTO dto);

    /**
     * 更新功能文档
     */
    void updateDocument(FeatureDocumentDTO dto);

    /**
     * 根据ID查询功能文档
     */
    FeatureDocumentDTO getDocumentById(Long id);

    /**
     * 分页查询功能文档
     */
    IPage<FeatureDocument> pageQuery(PageRequest request);

    /**
     * 删除功能文档
     */
    void deleteDocument(Long id);
}
