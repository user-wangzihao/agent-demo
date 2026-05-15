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

 Date: 15/05/2026 10:54:19
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
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'FAQ文档表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of faq_document
-- ----------------------------
INSERT INTO `faq_document` VALUES (1, '使用BOM导出Excel的表格为什么行数是乱的？', '[]', '导出EXCEL表的分页行数需要注意,不能超过原表格的最大行数,否则会超出行数乱码。', '[]', 4, 'BOM工具', 1, 0, '2026-05-13 11:54:34', '2026-05-14 17:45:46');
INSERT INTO `faq_document` VALUES (2, '使用建模档合并订料功能,弹出备料板重量超出限定值的的提示。要怎么改？', '[]', '对备料板的重量重新进行估算,使实际重量小于预估重量。', '[]', 6, '建模档合并订料', 1, 0, '2026-05-13 11:54:59', '2026-05-14 17:45:43');
INSERT INTO `faq_document` VALUES (3, '登录蓝U失败，提示已在其他电脑登录。', '[]', '等待约15秒后重新尝试登录，如果长时间登录失败，检查网络是否正常。也可选择联系运维人员处理', '[]', NULL, '', 1, 0, '2026-05-13 14:13:31', '2026-05-14 17:45:40');
INSERT INTO `faq_document` VALUES (4, '获取许可证失败报错', '[\"http://36.150.236.251:9000/agent-demo/2026/05/13/0b1f970b3c8649ee8b72959b1db8f719.png\"]', '提示“获取许可证失败”先重启UG和蓝U看是否解决，未解决可找实施咨询，\n掉许可证一般是蓝U掉了或实施有更新程序造成的。', '[]', NULL, '', 1, 0, '2026-05-13 14:19:15', '2026-05-14 17:45:37');
INSERT INTO `faq_document` VALUES (5, '登录蓝U提示密码错误', '[]', '默认密码为注册手机号，如果多次登录失败请联系管理员。', '[]', NULL, '', 1, 0, '2026-05-13 14:22:09', '2026-05-14 17:45:26');
INSERT INTO `faq_document` VALUES (6, '快速涂色功能报错：找不到配置表', '[]', '该功能需要优化，请等待版本更新。', '[]', 8, '快速涂色', 1, 0, '2026-05-15 10:28:22', '2026-05-15 10:28:23');

SET FOREIGN_KEY_CHECKS = 1;
