/*
 Navicat Premium Dump SQL

 Source Server         : 京东云
 Source Server Type    : MySQL
 Source Server Version : 90600 (9.6.0)
 Source Host           : 36.150.236.251:3306
 Source Schema         : ticket_system

 Target Server Type    : MySQL
 Target Server Version : 90600 (9.6.0)
 File Encoding         : 65001

 Date: 15/05/2026 10:55:31
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ticket
-- ----------------------------
DROP TABLE IF EXISTS `ticket`;
CREATE TABLE `ticket`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ticket_no` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '工单编号，如 TK-20240401-001',
  `source` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT 'AgentDemo' COMMENT '来源系统',
  `user_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '提交用户ID（来自AgentDemo）',
  `user_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '提交用户昵称',
  `agent_session_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'AgentDemo会话ID',
  `related_feature_id` bigint NULL DEFAULT NULL COMMENT '关联功能ID(AgentDemo feature_document.id,可为空)',
  `related_feature_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '通用FAQ' COMMENT '关联功能名(默认通用FAQ)',
  `title` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '问题标题（Agent自动总结）',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '问题描述（Agent总结的摘要）',
  `chat_history` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '完整对话历史（JSON数组格式）',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PENDING' COMMENT '工单状态',
  `priority` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'NORMAL' COMMENT '优先级',
  `handler_id` bigint NULL DEFAULT NULL COMMENT '处理人ID（sys_user.id）',
  `handler_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '处理人姓名',
  `resolution` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '处理结果（反馈给用户）',
  `remark` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '内部备注（不展示给用户）',
  `resolved_time` datetime NULL DEFAULT NULL COMMENT '处理完成时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ticket_no`(`ticket_no` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_agent_session_id`(`agent_session_id` ASC) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工单表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of ticket
-- ----------------------------
INSERT INTO `ticket` VALUES (7, 'TK-20260515-0001', 'AgentDemo', '1', '管理员', '52', NULL, '快速涂色', '快速涂色功能报错：找不到配置表', '使用快速涂色功能时弹出提示框，提示\'找不到配置表\'，导致上色失败。已尝试设置出图标准（如启泰）并确认特征注解表和材质颜色表路径，但问题仍未解决。', '[{\"role\":\"user\",\"content\":\"我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框,说是找不到配置表。这个问题要怎么解决？\"},{\"role\":\"assistant\",\"content\":\"您在使用「快速涂色」时遇到“找不到配置表”提示，导致上色失败。\\n\\n可能原因（按优先级）：  \\n1. **未设置出图标准**（最常见）——系统无法定位特征注解表和材质颜色表路径；  \\n2. 已设置标准但对应配置表（如“启泰”标准下的表）尚未加载或损坏。\\n\\n解决步骤：  \\n1. 进入【建模档出图标准设置】；  \\n2. 手动选择对应品牌的设计标准（例如：“启泰”）；  \\n3. 设置完成后，再依次打开【特征注解表】和【材质颜色表】确认可正常加载。\\n\\n预防措施：  \\n每次启动「快速涂色」前，务必先完成出图标准设置；建议在项目初始化阶段将常用标准设为默认。(参考:快速涂色-error_solution)\"},{\"role\":\"user\",\"content\":\"我试了，没有效果。这个提个工单\"}]', 'RESOLVED', 'NORMAL', 1, '管理员', '该功能需要优化，请等待版本更新。', '', '2026-05-15 10:03:41', 0, '2026-05-15 10:03:00', '2026-05-15 10:03:00');

SET FOREIGN_KEY_CHECKS = 1;
