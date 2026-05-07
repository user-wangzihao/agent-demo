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

 Date: 07/05/2026 15:06:56
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for video_document
-- ----------------------------
DROP TABLE IF EXISTS `video_document`;
CREATE TABLE `video_document`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `feature_id` bigint NULL DEFAULT NULL COMMENT '关联功能文档ID',
  `original_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '原始文件名',
  `file_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'MinIO存储URL',
  `file_size` bigint NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
  `file_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '文件类型（如 video/mp4）',
  `duration` double NULL DEFAULT NULL COMMENT '视频时长（秒），后续学习时可填充',
  `learn_status` tinyint NOT NULL DEFAULT 0 COMMENT '学习状态 0-未学习 1-学习中 2-已学习 3-学习失败',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_feature_id`(`feature_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '视频信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of video_document
-- ----------------------------
INSERT INTO `video_document` VALUES (1, 1, '赋注解属性.mp4', 'http://36.150.236.251:9000/agent-demo/2026/03/17/f886d77cf5cd4d48b706c248e22c9460.mp4', 35225059, 'video/mp4', NULL, 0, 0, '2026-03-17 11:08:39', '2026-03-17 11:08:39');

SET FOREIGN_KEY_CHECKS = 1;
