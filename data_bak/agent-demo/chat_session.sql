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

 Date: 15/05/2026 10:54:06
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
) ENGINE = InnoDB AUTO_INCREMENT = 53 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '会话表' ROW_FORMAT = Dynamic;

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
INSERT INTO `chat_session` VALUES (13, 1, '使用BOM导出Excel的表格为什么行数是乱的？', 0, '2026-05-07 16:59:14', '2026-05-07 16:59:28');
INSERT INTO `chat_session` VALUES (14, 1, '为甚使用BOM导出TXT文本失败？', 0, '2026-05-07 20:12:20', '2026-05-07 20:12:33');
INSERT INTO `chat_session` VALUES (15, 1, '使用建模档合并订料功能,弹出备料板重量超出限定值的的提示。要...', 0, '2026-05-07 20:41:00', '2026-05-07 20:41:05');
INSERT INTO `chat_session` VALUES (16, 1, '如何使用赋属性这个功能？', 0, '2026-05-07 20:47:02', '2026-05-07 20:47:08');
INSERT INTO `chat_session` VALUES (17, 1, '如何使用赋属性这个功能？说一下操作步骤', 0, '2026-05-07 20:48:13', '2026-05-07 20:48:19');
INSERT INTO `chat_session` VALUES (18, 1, '建模出图工具是做什么用的？', 0, '2026-05-08 08:25:56', '2026-05-08 08:26:09');
INSERT INTO `chat_session` VALUES (19, 1, '建模档出图预设功能需要填写哪些公共信息？', 0, '2026-05-08 08:26:43', '2026-05-08 08:26:47');
INSERT INTO `chat_session` VALUES (20, 1, '建模档合并订料的旋转功能,有几种旋转方式？', 0, '2026-05-08 08:27:37', '2026-05-08 08:27:41');
INSERT INTO `chat_session` VALUES (21, 1, '快速涂色功能具体如何使用？', 0, '2026-05-08 08:30:16', '2026-05-08 08:30:23');
INSERT INTO `chat_session` VALUES (22, 1, '新对话', 0, '2026-05-09 13:59:45', '2026-05-09 13:59:45');
INSERT INTO `chat_session` VALUES (23, 1, '如何使用赋属性这个功能？', 0, '2026-05-09 14:01:25', '2026-05-09 14:01:40');
INSERT INTO `chat_session` VALUES (24, 1, '为什么导出的图没有注解？', 0, '2026-05-09 14:04:01', '2026-05-09 14:04:09');
INSERT INTO `chat_session` VALUES (25, 1, '建模出图工具是做什么用的？', 0, '2026-05-09 14:06:23', '2026-05-09 14:06:29');
INSERT INTO `chat_session` VALUES (26, 1, '备料板尺寸的大小是否会随前距、后距、左距、右距的值变化而产生...', 0, '2026-05-09 14:08:46', '2026-05-09 14:08:53');
INSERT INTO `chat_session` VALUES (27, 1, '零件刻字', 0, '2026-05-09 14:09:50', '2026-05-09 14:09:56');
INSERT INTO `chat_session` VALUES (28, 1, '如何使用BOM表工具这个功能？', 0, '2026-05-09 14:10:54', '2026-05-09 14:11:01');
INSERT INTO `chat_session` VALUES (29, 1, '使用BOM导出Excel的表格为什么行数是乱的？', 0, '2026-05-09 14:12:05', '2026-05-09 14:12:12');
INSERT INTO `chat_session` VALUES (30, 1, '新对话', 0, '2026-05-09 15:52:57', '2026-05-09 15:52:57');
INSERT INTO `chat_session` VALUES (31, 1, '新对话', 0, '2026-05-12 17:07:55', '2026-05-12 17:07:55');
INSERT INTO `chat_session` VALUES (32, 1, '新对话', 0, '2026-05-12 17:08:43', '2026-05-12 17:08:43');
INSERT INTO `chat_session` VALUES (33, 1, '你好', 0, '2026-05-12 17:26:28', '2026-05-12 17:26:36');
INSERT INTO `chat_session` VALUES (34, 1, '建模档出图标准怎么设置', 0, '2026-05-12 17:29:08', '2026-05-12 17:29:17');
INSERT INTO `chat_session` VALUES (35, 1, '你上面问题说提到的图纸路径，默认在哪个路径下？', 0, '2026-05-12 19:16:34', '2026-05-12 19:16:47');
INSERT INTO `chat_session` VALUES (36, 1, '我登陆蓝U的时候，总是说我已经登录了，要怎么处理？', 0, '2026-05-13 14:47:43', '2026-05-13 14:47:54');
INSERT INTO `chat_session` VALUES (37, 1, '我登陆蓝U的时候，总是说我已经登录了，要怎么处理？', 0, '2026-05-13 15:00:42', '2026-05-13 15:00:53');
INSERT INTO `chat_session` VALUES (38, 1, '使用BOM导出Excel的表格为什么行数是乱的？', 0, '2026-05-13 15:03:55', '2026-05-13 15:04:02');
INSERT INTO `chat_session` VALUES (39, 1, '快速涂色功能具体如何使用？', 0, '2026-05-13 15:10:34', '2026-05-13 15:10:40');
INSERT INTO `chat_session` VALUES (40, 1, '使用BOM导出Excel的表格为什么行数是乱的？', 0, '2026-05-13 15:13:40', '2026-05-13 15:13:47');
INSERT INTO `chat_session` VALUES (41, 1, '使用BOM导出Excel的表格为什么行数是乱的？', 0, '2026-05-13 15:16:46', '2026-05-13 15:16:59');
INSERT INTO `chat_session` VALUES (42, 1, '新对话', 0, '2026-05-13 15:33:44', '2026-05-13 15:33:44');
INSERT INTO `chat_session` VALUES (43, 1, '使用BOM导出Excel的表格为什么行数是乱的？', 0, '2026-05-13 15:46:44', '2026-05-13 15:46:59');
INSERT INTO `chat_session` VALUES (44, 1, '我登陆蓝U的时候，总是说我已经登录了，要怎么处理？', 0, '2026-05-13 15:48:39', '2026-05-13 15:48:52');
INSERT INTO `chat_session` VALUES (45, 1, '使用BOM导出Excel的表格为什么行数是乱的？', 0, '2026-05-13 15:49:58', '2026-05-13 15:50:05');
INSERT INTO `chat_session` VALUES (46, 1, '我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框...', 0, '2026-05-14 20:04:09', '2026-05-14 20:04:21');
INSERT INTO `chat_session` VALUES (47, 1, '我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框...', 1, '2026-05-14 20:44:32', '2026-05-15 08:06:30');
INSERT INTO `chat_session` VALUES (48, 1, '我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框...', 1, '2026-05-15 08:04:58', '2026-05-15 08:06:28');
INSERT INTO `chat_session` VALUES (49, 1, '我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框...', 0, '2026-05-15 08:06:52', '2026-05-15 08:06:58');
INSERT INTO `chat_session` VALUES (50, 1, '我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框...', 0, '2026-05-15 08:25:12', '2026-05-15 08:25:25');
INSERT INTO `chat_session` VALUES (51, 1, '我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框...', 0, '2026-05-15 09:39:48', '2026-05-15 09:40:02');
INSERT INTO `chat_session` VALUES (52, 1, '我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框...', 0, '2026-05-15 10:02:40', '2026-05-15 10:02:52');

SET FOREIGN_KEY_CHECKS = 1;
