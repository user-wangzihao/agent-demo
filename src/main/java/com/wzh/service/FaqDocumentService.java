package com.wzh.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wzh.entity.FaqDocument;
import com.wzh.entity.dto.FaqDocumentDTO;
import com.wzh.entity.dto.PageRequest;

public interface FaqDocumentService extends IService<FaqDocument> {

    Long addFaq(FaqDocumentDTO dto);

    void updateFaq(FaqDocumentDTO dto);

    FaqDocumentDTO getFaqById(Long id);

    IPage<FaqDocument> pageQuery(PageRequest request);

    void deleteFaq(Long id);
}