/*
 Navicat Premium Dump SQL

 Source Server         : 京东云
 Source Server Type    : MySQL
 Source Server Version : 90600 (9.6.0)
 Source Host           : 36.150.236.251:3337
 Source Schema         : ticket_system

 Target Server Type    : MySQL
 Target Server Version : 90600 (9.6.0)
 File Encoding         : 65001

 Date: 19/05/2026 17:45:34
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ticket_faq_candidate
-- ----------------------------
DROP TABLE IF EXISTS `ticket_faq_candidate`;
CREATE TABLE `ticket_faq_candidate`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ticket_id` bigint NOT NULL COMMENT '关联工单ID(ticket.id)',
  `ticket_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '工单编号(冗余便于查询)',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '问题描述',
  `question_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '问题关联图片URL JSON数组',
  `answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '答案内容',
  `answer_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '答案关联图片URL JSON数组',
  `related_feature_id` bigint NULL DEFAULT NULL COMMENT '关联功能ID(可空)',
  `related_feature_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '通用FAQ' COMMENT '关联功能名(空=通用FAQ)',
  `marked_by_id` bigint NOT NULL COMMENT '标记技术员ID(sys_user.id)',
  `marked_by_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '标记技术员姓名',
  `submit_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'EDITING' COMMENT '提交状态: EDITING-编辑中 / SUBMITTED-已提交给Agent',
  `submitted_time` datetime NULL DEFAULT NULL COMMENT '正式提交给Agent的时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ticket_id`(`ticket_id` ASC) USING BTREE COMMENT '一个工单最多对应一条候选',
  INDEX `idx_marked_by_id`(`marked_by_id` ASC) USING BTREE COMMENT '技术员视角列表查询',
  INDEX `idx_submit_status`(`submit_status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工单FAQ候选编辑表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of ticket_faq_candidate
-- ----------------------------
INSERT INTO `ticket_faq_candidate` VALUES (2, 7, 'TK-20260515-0001', '快速涂色功能报错：找不到配置表', '[]', '该功能需要优化，请等待版本更新。', '[]', NULL, '快速涂色', 1, '管理员', 'SUBMITTED', '2026-05-15 10:03:52', 0, '2026-05-15 10:03:41', '2026-05-15 10:03:52');

SET FOREIGN_KEY_CHECKS = 1;
