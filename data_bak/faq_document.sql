/*
 Navicat Premium Dump SQL

 Source Server         : 京东云
 Source Server Type    : MySQL
 Source Server Version : 90600 (9.6.0)
 Source Host           : 36.150.236.251:3306
 Source Schema         : agent_demo

 Target Server Type    : MySQL
 Target Server Version : 90600 (9.6.0)
 File Encoding         : 65001

 Date: 07/05/2026 15:05:54
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for faq_document
-- ----------------------------
DROP TABLE IF EXISTS `faq_document`;
CREATE TABLE `faq_document`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '问题内容',
  `question_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '问题关联图片 JSON',
  `answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '答案内容',
  `answer_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '答案关联图片 JSON',
  `related_feature_id` bigint NULL DEFAULT NULL COMMENT '关联功能文档ID',
  `related_feature_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '关联功能名称',
  `vectorized` tinyint NOT NULL DEFAULT 0 COMMENT '是否已向量化 0-未向量化 1-已向量化',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-未删除 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_related_feature_id`(`related_feature_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'FAQ文档表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of faq_document
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
