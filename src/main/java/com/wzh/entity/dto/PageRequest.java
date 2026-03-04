package com.wzh.entity.dto;

import lombok.Data;

@Data
public class PageRequest {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 搜索关键词（按功能名称模糊搜索） */
    private String keyword;
}