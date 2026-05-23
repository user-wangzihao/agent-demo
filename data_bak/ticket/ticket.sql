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

 Date: 23/05/2026 15:17:16
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
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工单表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of ticket
-- ----------------------------
INSERT INTO `ticket` VALUES (7, 'TK-20260515-0001', 'AgentDemo', '1', '管理员', '52', NULL, '快速涂色', '快速涂色功能报错：找不到配置表', '使用快速涂色功能时弹出提示框，提示\'找不到配置表\'，导致上色失败。已尝试设置出图标准（如启泰）并确认特征注解表和材质颜色表路径，但问题仍未解决。', '[{\"role\":\"user\",\"content\":\"我在使用快速涂色功能的时候,为什么上色功能失败？弹出个提示框,说是找不到配置表。这个问题要怎么解决？\"},{\"role\":\"assistant\",\"content\":\"您在使用「快速涂色」时遇到“找不到配置表”提示，导致上色失败。\\n\\n可能原因（按优先级）：  \\n1. **未设置出图标准**（最常见）——系统无法定位特征注解表和材质颜色表路径；  \\n2. 已设置标准但对应配置表（如“启泰”标准下的表）尚未加载或损坏。\\n\\n解决步骤：  \\n1. 进入【建模档出图标准设置】；  \\n2. 手动选择对应品牌的设计标准（例如：“启泰”）；  \\n3. 设置完成后，再依次打开【特征注解表】和【材质颜色表】确认可正常加载。\\n\\n预防措施：  \\n每次启动「快速涂色」前，务必先完成出图标准设置；建议在项目初始化阶段将常用标准设为默认。(参考:快速涂色-error_solution)\"},{\"role\":\"user\",\"content\":\"我试了，没有效果。这个提个工单\"}]', 'RESOLVED', 'NORMAL', 1, '管理员', '该功能需要优化，请等待版本更新。', '', '2026-05-15 10:03:41', 0, '2026-05-15 10:03:00', '2026-05-15 10:03:00');
INSERT INTO `ticket` VALUES (8, 'TK-20260519-0001', 'AgentDemo', '3', '普通用户', '59', NULL, 'BOM工具', 'BOM工具操作步骤存在疑问，需人工确认', '用户反馈BOM工具使用步骤存在问题，希望人工核实并指导正确操作流程。', '[{\"role\":\"user\",\"content\":\"BOM 工具怎么用？\"},{\"role\":\"assistant\",\"content\":\"以下是使用 BOM 工具的完整操作步骤（参考知识库操作指南）：\\n\\n1. **填写并检查客户信息、模号、产品编号**（位于工具主界面顶部输入区）  \\n2. **选择 BOM 表类型**（如材料清单、标准件清单、成本预算单）  \\n3. **按需勾选选项**：  \\n　　- ✅ 已备料不出料单  \\n　　- ✅ 是否分页 → 设置**分页类型和行数**（注意：行数不可超过原表格最大行数，如 20，否则导致乱码）  \\n4. **生成 BOM 数据**：  \\n　　- 点击**扫描所有实体**（全图档）或**选择实体生成**（仅选中对象）  \\n5. **导出结果**：点击**导出EXCEL**；导出后如需文本，可再点**EXCEL转TXT文本**  \\n\\n⚠️ 前置条件：必须完成步骤1～3后，步骤4扫描才有正确表头与字段；未扫描则步骤5导出为空或报错。  \\n（参考: BOM工具-operation_guide）\"},{\"role\":\"user\",\"content\":\"步骤有问题，转人工。\"}]', 'PENDING', 'NORMAL', NULL, NULL, NULL, NULL, NULL, 0, '2026-05-19 11:19:58', '2026-05-19 11:19:58');
INSERT INTO `ticket` VALUES (9, 'TK-20260522-0001', 'AgentDemo', '1', '管理员', '217', NULL, 'BOM工具', '咨询BOM工具使用方法', '用户询问如何使用BOM工具，需要人工进一步指导或提供详细操作说明。', '[{\"role\":\"user\",\"content\":\"怎么使用 BOM 工具？\"},{\"role\":\"assistant\",\"content\":\"以下是使用 BOM 工具的完整操作步骤：\\n\\n1. **填写并检查客户信息、模号、产品编号**（前置条件：必须准确填写真实有效的信息，将写入EXCEL表头及TXT元数据，是法定追溯依据）  \\n2. **选择BOM表类型**（如材料清单、标准件清单、成本预算单）  \\n3. **按需勾选选项**：  \\n　　- 勾选「已备料不出料单」  \\n　　- 设置「是否分页」及「分页类型和行数」（注意：分页行数不能超过原表格最大行数，如20，否则导致乱码）  \\n4. **生成BOM表**：点击「扫描所有实体出BOM表」或「选择实体生成」  \\n5. **导出与转换**：点击「导出EXCEL」；如需进一步处理，再点击「EXCEL转TXT文本」（该功能仅在导出EXCEL后可用）  \\n\\n(参考:BOM工具-operation_guide)\"},{\"role\":\"user\",\"content\":\"转人工\"}]', 'PENDING', 'NORMAL', NULL, NULL, NULL, NULL, NULL, 0, '2026-05-22 10:06:38', '2026-05-22 10:06:38');
INSERT INTO `ticket` VALUES (10, 'TK-20260522-0002', 'AgentDemo', '1', '管理员', '220', NULL, 'BOM工具', '咨询BOM工具使用方法', '用户询问如何使用BOM工具，需要人工进一步指导操作细节。', '[{\"role\":\"user\",\"content\":\"怎么使用 BOM 工具\"},{\"role\":\"assistant\",\"content\":\"以下是使用 BOM 工具的完整操作步骤：\\n\\n1. **填写并检查客户信息、模号、产品编号**（前置条件：必须准确填写真实有效的图层编码、模号、产品编号，不可留空或用占位符）  \\n2. **选择BOM表类型**（如材料清单、标准件清单、成本预算单）  \\n3. **按需勾选选项**：如“已备料不出料单”、是否分页，并设置**分页类型和行数**（注意：分页行数不能超过原表格最大行数，如20，否则会导致乱码）  \\n4. **生成BOM表**：点击“扫描所有实体”或手动“选择实体”生成  \\n5. **导出EXCEL**：点击“导出EXCEL”按钮；导出后如需，可再点击“EXCEL转TXT文本”  \\n\\n（参考:BOM工具-operation_guide）\"},{\"role\":\"user\",\"content\":\"转人工\"}]', 'PENDING', 'NORMAL', NULL, NULL, NULL, NULL, NULL, 0, '2026-05-22 10:31:27', '2026-05-22 10:31:27');
INSERT INTO `ticket` VALUES (11, 'TK-20260523-0001', 'AgentDemo', '1', '管理员', '224', NULL, 'BOM工具', 'BOM工具使用步骤存在疑问', '用户反馈提供的BOM工具使用步骤存在问题，需要技术人员核实并优化说明文档。', '[{\"role\":\"user\",\"content\":\"如何使用bom工具呢？\"},{\"role\":\"assistant\",\"content\":\"以下是使用BOM工具的完整操作步骤：\\n\\n1. **填写并检查客户信息、模号、产品编号**（前置条件：必须准确填写真实有效的信息，将写入EXCEL表头及TXT元数据，是法定追溯依据）  \\n2. **选择BOM表类型**（如材料清单、标准件清单、成本预算单）  \\n3. **按需勾选选项**：如“已备料不出料单”，设置“是否分页”及**分页行数（不可超过原表格最大行数，如20，否则导致乱码）**  \\n4. **扫描所有实体出BOM表**，或**手动选择实体生成**（依赖前两步信息填充表头与字段范围）  \\n5. **点击“导出EXCEL”**；导出后如需，可再点击“EXCEL转TXT文本”\\n\\n（参考:BOM工具-operation_guide）\"},{\"role\":\"user\",\"content\":\"步骤有些问题，帮我提交一个工单。\"}]', 'PENDING', 'NORMAL', NULL, NULL, NULL, NULL, NULL, 0, '2026-05-23 11:22:08', '2026-05-23 11:22:08');

SET FOREIGN_KEY_CHECKS = 1;
