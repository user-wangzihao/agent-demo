/*
 Navicat Premium Dump SQL

 Source Server         : 京东云
 Source Server Type    : MySQL
 Source Server Version : 90600 (9.6.0)
 Source Host           : 36.150.236.251:3337
 Source Schema         : agent_demo

 Target Server Type    : MySQL
 Target Server Version : 90600 (9.6.0)
 File Encoding         : 65001

 Date: 23/05/2026 15:16:42
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for semantic_cache
-- ----------------------------
DROP TABLE IF EXISTS `semantic_cache`;
CREATE TABLE `semantic_cache`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cache_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '缓存唯一键，MD5(featureName + 归一化后的query)，L1精确命中和L2语义命中都通过此键定位记录',
  `feature_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '所属功能名，用于按 featureName 批量失效（管理员重学文档/操作FAQ时触发）',
  `query_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原始用户问题，用于人工审查\"哪些缓存被DEGRADED\"',
  `answer_text` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '缓存的AI回答全文，命中后直接吐给前端',
  `source_info` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'JSON序列化的SourceInfo列表，命中时随回答一起返回前端，用于\"引用来源\"展示',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态机：ACTIVE=正常可命中；DEGRADED=负反馈超阈值，命中时跳过缓存重新生成；INVALID=管理员主动失效，永不命中',
  `hit_count` int NOT NULL DEFAULT 0 COMMENT '命中次数，统计用，每次L1或L2命中后+1',
  `feedback_score` int NOT NULL DEFAULT 0 COMMENT '负反馈加权分。点踩+2，重新生成+1，提交工单+3。≥feedback-threshold(默认5)时自动置DEGRADED',
  `last_hit_time` datetime NULL DEFAULT NULL COMMENT '最后一次命中时间，用于淘汰策略（长期未命中的低价值缓存）',
  `expire_at` datetime NOT NULL COMMENT '过期时间(写入时now()+24h)。Redis TTL自然过期不通知MySQL，靠定时任务定期清理expire_at<now()的记录',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_cache_key`(`cache_key` ASC) USING BTREE,
  INDEX `idx_feature_name`(`feature_name` ASC) USING BTREE COMMENT '管理员批量失效查询用',
  INDEX `idx_status`(`status` ASC) USING BTREE COMMENT '定时任务扫描ACTIVE/DEGRADED分布用',
  INDEX `idx_expire_at`(`expire_at` ASC) USING BTREE COMMENT '定时任务清理过期记录用'
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '语义缓存元数据表（L1精确+L2语义双层共用），与Redis存answer正文+Milvus存query向量配合工作' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of semantic_cache
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
