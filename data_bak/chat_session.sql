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

 Date: 07/05/2026 15:05:41
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for chat_session
-- ----------------------------
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '新对话' COMMENT '会话标题',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-未删除 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '会话表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of chat_session
-- ----------------------------
INSERT INTO `chat_session` VALUES (1, 1, '我在使用快速改色工具时错误，提示“错误：找不到配置表：E:\\...', 0, '2026-03-01 08:26:22', '2026-03-01 08:26:27');
INSERT INTO `chat_session` VALUES (2, 1, '我在使用快速改色工具时错误，提示“错误：找不到配置表：E:\\...', 0, '2026-03-01 08:58:47', '2026-03-01 08:58:50');
INSERT INTO `chat_session` VALUES (3, 1, '我在使用快速改色工具时错误，提示“错误：找不到配置表：E:\\...', 0, '2026-03-01 09:47:27', '2026-03-01 09:47:53');
INSERT INTO `chat_session` VALUES (4, 1, '我使用“赋注解属性工具”这个功能，结果弹出一个提示框。内容如...', 0, '2026-03-01 17:21:38', '2026-03-01 17:21:56');
INSERT INTO `chat_session` VALUES (5, 1, '我使用“赋注解属性工具”这个功能，结果弹出一个提示框。内容如...', 0, '2026-04-20 20:10:28', '2026-04-20 20:10:40');
INSERT INTO `chat_session` VALUES (6, 1, '知识库里哪些文档还没学习？', 0, '2026-04-20 20:11:18', '2026-04-20 20:11:56');
INSERT INTO `chat_session` VALUES (7, 2, '知识库里哪些文档还没学习？', 0, '2026-04-20 20:16:36', '2026-04-20 20:16:40');
INSERT INTO `chat_session` VALUES (8, 1, '这个问题交给人工处理：我使用“赋注解属性工具”这个功能，结果...', 1, '2026-04-20 20:39:13', '2026-04-21 19:41:27');
INSERT INTO `chat_session` VALUES (9, 1, '这个问题交给人工处理：我使用“赋注解属性工具”这个功能，结果...', 1, '2026-04-21 19:34:59', '2026-04-21 19:41:26');
INSERT INTO `chat_session` VALUES (10, 1, '这个问题交给人工处理：我使用“赋注解属性工具”这个功能，结果...', 0, '2026-04-21 19:41:33', '2026-04-21 19:41:39');
INSERT INTO `chat_session` VALUES (11, 1, '这个问题交给人工处理：我使用“赋注解属性工具”这个功能，结果...', 0, '2026-04-21 19:48:05', '2026-04-21 19:48:18');
INSERT INTO `chat_session` VALUES (12, 1, '知识库里面还有那些文档没有学习？', 0, '2026-04-22 14:11:19', '2026-04-22 14:11:27');

SET FOREIGN_KEY_CHECKS = 1;
