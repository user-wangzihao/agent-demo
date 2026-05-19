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

 Date: 19/05/2026 17:44:16
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for faq_candidate
-- ----------------------------
DROP TABLE IF EXISTS `faq_candidate`;
CREATE TABLE `faq_candidate`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `source_ticket_id` bigint NOT NULL COMMENT '来源工单ID(TicketSystem ticket.id)',
  `source_ticket_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '来源工单编号',
  `submitted_by_id` bigint NOT NULL COMMENT '提交技术员ID',
  `submitted_by_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '提交技术员姓名',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '问题',
  `question_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '问题图片JSON',
  `answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '答案',
  `answer_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '答案图片JSON',
  `related_feature_id` bigint NULL DEFAULT NULL COMMENT '关联功能ID(可空)',
  `related_feature_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '通用FAQ' COMMENT '关联功能名',
  `review_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PENDING' COMMENT '审核状态: PENDING-待审核 / LEARNED-已学习 / REJECTED-已拒绝',
  `reviewer_id` bigint NULL DEFAULT NULL COMMENT '审核管理员ID',
  `reviewer_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '审核管理员姓名',
  `reviewer_note` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '审核备注(拒绝时填理由)',
  `reviewed_time` datetime NULL DEFAULT NULL COMMENT '审核时间',
  `promoted_faq_id` bigint NULL DEFAULT NULL COMMENT '学习后生成的faq_document.id',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_source_ticket`(`source_ticket_id` ASC) USING BTREE COMMENT '一个工单最多对应一条候选',
  INDEX `idx_review_status`(`review_status` ASC) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'FAQ候选池(来自工单系统)' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of faq_candidate
-- ----------------------------
INSERT INTO `faq_candidate` VALUES (1, 7, 'TK-20260515-0001', 1, '管理员', '快速涂色功能报错：找不到配置表', '[]', '该功能需要优化，请等待版本更新。', '[]', 8, '快速涂色', 'LEARNED', 1, 'admin', NULL, '2026-05-15 10:28:23', 6, 0, '2026-05-15 10:03:52', '2026-05-15 10:28:23');

SET FOREIGN_KEY_CHECKS = 1;
