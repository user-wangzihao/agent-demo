package com.wzh.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wzh.agentdemo.common.entity.VideoDocument;
import com.wzh.agentdemo.common.mapper.VideoDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class VideoDocumentService extends ServiceImpl<VideoDocumentMapper, VideoDocument> {

    /**
     * 查询某个功能文档关联的所有视频
     */
    public List<VideoDocument> getByFeatureId(Long featureId) {
        return this.list(new LambdaQueryWrapper<VideoDocument>()
                .eq(VideoDocument::getFeatureId, featureId)
                .orderByAsc(VideoDocument::getCreateTime));
    }

    /**
     * 删除某个功能文档关联的所有视频记录
     */
    public void removeByFeatureId(Long featureId) {
        this.remove(new LambdaQueryWrapper<VideoDocument>()
                .eq(VideoDocument::getFeatureId, featureId));
    }
}