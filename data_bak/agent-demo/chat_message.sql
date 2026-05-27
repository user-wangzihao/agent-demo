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

 Date: 27/05/2026 08:06:45
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for chat_message
-- ----------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `session_id` bigint NOT NULL COMMENT '会话ID',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色: user / assistant',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息内容',
  `feature_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '本轮匹配到的功能名,chitchat=闲聊,NULL=未匹配',
  `related_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '关联图片URL列表 JSON',
  `sources` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '引用来源 JSON',
  `user_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '用户上传的图片URL列表 JSON',
  `feedback_rating` tinyint NULL DEFAULT NULL COMMENT '反馈评分: 1-点赞 -1-点踩',
  `feedback_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '反馈原因',
  `feedback_time` datetime NULL DEFAULT NULL COMMENT '反馈时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `faq_hit` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'B4: assistant 行=本轮回答是否采纳 FAQ 来源 (sources 含 chunkType=FAQ); user 行恒为 FALSE 不维护',
  `cache_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '关联的语义缓存key。assistant行：①缓存命中(L1/L2)→填命中的cache_key；②未命中且新生成→填本次写回的cache_key；③不写缓存的场景(chitchat/admin/ticket/faqHit)→NULL；user行：恒为NULL。用于负反馈三入口反查累加feedback_score，确保新生成的低质量答案在首次点踩时就开始累积降级分',
  `cache_hit_layer` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '本次回答的缓存命中层级：L1=精确命中，L2=语义命中，NULL=未命中（可能是新写入了缓存，也可能是不缓存的场景）。配合cache_key使用区分\"命中消费\"vs\"新生成写回\"',
  `submitted_ticket_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'B5: 用户点工单按钮后的工单号(TK-yyyyMMdd-NNNN)/SUBMITTING占位/null未提单',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_session_id`(`session_id` ASC) USING BTREE,
  INDEX `idx_cache_key`(`cache_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 552 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '聊天消息表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of chat_message
-- ----------------------------
INSERT INTO `chat_message` VALUES (541, 240, 'user', 'BOM工具怎么使用', 'BOM工具', NULL, NULL, NULL, NULL, NULL, NULL, '2026-05-26 14:16:44', 0, NULL, NULL, NULL);
INSERT INTO `chat_message` VALUES (543, 240, 'assistant', '以下是 BOM 工具的标准使用步骤（操作指引）：\n\n1. **填写并检查客户信息、模号、产品编号**（前置条件：必须准确填写真实有效的信息，将写入EXCEL表头及TXT元数据，不可留空或用占位符）  \n2. **选择BOM表类型**（如材料清单、标准件清单、成本预算单）  \n3. **按需勾选选项**：如“已备料不出料单”，设置“是否分页”及**分页行数（不可超过原表格最大行数，如20，否则导致乱码）**  \n4. **生成BOM表**：点击“扫描所有实体”或“选择实体生成”（依赖步骤一、二的输入）  \n5. **导出与转换**：点击**导出EXCEL**；导出后如需，再点击“EXCEL转TXT文本”  \n\n（参考:BOM工具-operation_guide）', 'BOM工具', '[\"http://36.150.236.251:9000/agent-demo/2026/02/28/39639fc7e9a44ae291bd620270dd0dee.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/01489e8042b6431e88e7f16506fa9720.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/74da6fb136c2470dbe0d28035a612ec1.png\",\"http://36.150.236.251:9000/agent-demo/2026/05/13/0b1f970b3c8649ee8b72959b1db8f719.png\"]', '[{\"featureName\":\"使用BOM导出Excel的表格为什么行数是乱的？\",\"chunkType\":\"FAQ\",\"score\":0.5279889},{\"featureName\":\"获取许可证失败报错\",\"chunkType\":\"FAQ\",\"score\":0.31462875},{\"featureName\":\"BOM工具\",\"chunkType\":\"operation_guide\",\"score\":0.8060397},{\"featureName\":\"BOM工具\",\"chunkType\":\"dependency\",\"score\":0.6962864},{\"featureName\":\"BOM工具\",\"chunkType\":\"caution\",\"score\":0.68995696}]', NULL, -1, '答案太啰嗦', '2026-05-26 14:18:35', '2026-05-26 14:17:51', 1, NULL, NULL, NULL);
INSERT INTO `chat_message` VALUES (544, 241, 'user', 'BOM工具怎么使用', 'BOM工具', NULL, NULL, NULL, NULL, NULL, NULL, '2026-05-26 14:23:52', 0, NULL, NULL, NULL);
INSERT INTO `chat_message` VALUES (545, 241, 'assistant', '以下是 BOM 工具的标准使用步骤（操作指引）：\n\n1. **填写并检查客户信息、模号、产品编号**（前置条件：必须准确填写真实有效的信息，将写入EXCEL表头及TXT元数据，不可留空或用占位符）  \n2. **选择BOM表类型**（如材料清单、标准件清单、成本预算单）  \n3. **按需勾选选项**：如“已备料不出料单”，设置“是否分页”及**分页行数（不可超过原表格最大行数，如20，否则导致乱码）**  \n4. **生成BOM表**：点击“扫描所有实体”或“选择实体生成”（依赖步骤一、二的输入）  \n5. **导出与转换**：点击**导出EXCEL**；导出后如需，再点击“EXCEL转TXT文本”  \n\n（参考:BOM工具-operation_guide）', 'BOM工具', '[\"http://36.150.236.251:9000/agent-demo/2026/02/28/39639fc7e9a44ae291bd620270dd0dee.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/01489e8042b6431e88e7f16506fa9720.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/74da6fb136c2470dbe0d28035a612ec1.png\",\"http://36.150.236.251:9000/agent-demo/2026/05/13/0b1f970b3c8649ee8b72959b1db8f719.png\"]', '[{\"featureName\":\"使用BOM导出Excel的表格为什么行数是乱的？\",\"chunkType\":\"FAQ\",\"score\":0.5279889},{\"featureName\":\"获取许可证失败报错\",\"chunkType\":\"FAQ\",\"score\":0.31462875},{\"featureName\":\"BOM工具\",\"chunkType\":\"operation_guide\",\"score\":0.8060397},{\"featureName\":\"BOM工具\",\"chunkType\":\"dependency\",\"score\":0.6962864},{\"featureName\":\"BOM工具\",\"chunkType\":\"caution\",\"score\":0.68995696}]', NULL, -1, '答案太啰嗦', '2026-05-26 14:24:04', '2026-05-26 14:23:54', 1, '04bedaf8667b51151c1f55bf3868c024', 'L1', NULL);
INSERT INTO `chat_message` VALUES (546, 242, 'user', 'BOM工具怎么使用', 'BOM工具', NULL, NULL, NULL, NULL, NULL, NULL, '2026-05-26 15:21:06', 0, NULL, NULL, NULL);
INSERT INTO `chat_message` VALUES (547, 242, 'assistant', '以下是 BOM 工具的标准使用步骤（操作指引）：\n\n1. **填写并检查客户信息、模号、产品编号**（前置条件：必须准确填写真实有效的信息，将写入EXCEL表头及TXT元数据，不可留空或用占位符）  \n2. **选择BOM表类型**（如材料清单、标准件清单、成本预算单）  \n3. **按需勾选选项**：如“已备料不出料单”，设置“是否分页”及**分页行数（不可超过原表格最大行数，如20，否则导致乱码）**  \n4. **生成BOM表**：点击“扫描所有实体”或“选择实体生成”（依赖步骤一、二的输入）  \n5. **导出与转换**：点击**导出EXCEL**；导出后如需，再点击“EXCEL转TXT文本”  \n\n（参考:BOM工具-operation_guide）', 'BOM工具', '[\"http://36.150.236.251:9000/agent-demo/2026/02/28/39639fc7e9a44ae291bd620270dd0dee.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/01489e8042b6431e88e7f16506fa9720.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/74da6fb136c2470dbe0d28035a612ec1.png\",\"http://36.150.236.251:9000/agent-demo/2026/05/13/0b1f970b3c8649ee8b72959b1db8f719.png\"]', '[{\"featureName\":\"使用BOM导出Excel的表格为什么行数是乱的？\",\"chunkType\":\"FAQ\",\"score\":0.5279889},{\"featureName\":\"获取许可证失败报错\",\"chunkType\":\"FAQ\",\"score\":0.31462875},{\"featureName\":\"BOM工具\",\"chunkType\":\"operation_guide\",\"score\":0.8060397},{\"featureName\":\"BOM工具\",\"chunkType\":\"dependency\",\"score\":0.6962864},{\"featureName\":\"BOM工具\",\"chunkType\":\"caution\",\"score\":0.68995696}]', NULL, NULL, NULL, NULL, '2026-05-26 15:21:19', 1, '04bedaf8667b51151c1f55bf3868c024', NULL, NULL);
INSERT INTO `chat_message` VALUES (548, 243, 'user', 'BOM工具怎么使用', 'BOM工具', NULL, NULL, NULL, NULL, NULL, NULL, '2026-05-26 15:27:03', 0, NULL, NULL, NULL);
INSERT INTO `chat_message` VALUES (549, 243, 'assistant', '以下是BOM工具的使用步骤（适用于材料清单、标准件清单、成本预算单）：\n\n1. **填写并检查客户信息、模号、产品编号**（前置条件：确保信息准确，用于标识BOM来源）  \n2. **选择BOM表类型**（如材料清单/标准件清单/成本预算单）  \n3. **按需勾选“已备料不出料单”，设置分页参数**：  \n　　- 勾选是否分页  \n　　- 选择分页类型（如按图号/按零件）  \n　　- **设置分页行数（注意：不可超过原表格最大行数，如20，否则导出EXCEL会乱码）**  \n4. **生成BOM数据**：  \n　　- 点击“扫描所有实体出BOM表”，或  \n　　- **选择特定实体后点击“生成”**  \n5. **导出与转换**：  \n　　- 点击**导出EXCEL**（必须先生成BOM数据，否则无内容可导）  \n　　- 如需文本格式，导出后点击**EXCEL转TXT文本**  \n\n（参考:BOM工具-operation_guide）', 'BOM工具', '[\"http://36.150.236.251:9000/agent-demo/2026/02/28/39639fc7e9a44ae291bd620270dd0dee.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/74da6fb136c2470dbe0d28035a612ec1.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/01489e8042b6431e88e7f16506fa9720.png\",\"http://36.150.236.251:9000/agent-demo/2026/05/13/0b1f970b3c8649ee8b72959b1db8f719.png\"]', '[{\"featureName\":\"使用BOM导出Excel的表格为什么行数是乱的？\",\"chunkType\":\"FAQ\",\"score\":0.5279889},{\"featureName\":\"获取许可证失败报错\",\"chunkType\":\"FAQ\",\"score\":0.31462875},{\"featureName\":\"BOM工具\",\"chunkType\":\"operation_guide\",\"score\":0.8060397},{\"featureName\":\"BOM工具\",\"chunkType\":\"dependency\",\"score\":0.69390225}]', NULL, NULL, NULL, NULL, '2026-05-26 15:27:10', 1, '04bedaf8667b51151c1f55bf3868c024', NULL, NULL);
INSERT INTO `chat_message` VALUES (550, 243, 'user', '回答不对，提交个工单', 'BOM工具', NULL, NULL, NULL, NULL, NULL, NULL, '2026-05-26 19:55:37', 0, NULL, NULL, NULL);
INSERT INTO `chat_message` VALUES (551, 243, 'assistant', '好的,我来帮您提交工单,请稍候。  \n好的，我来帮您提交工单，请稍候。  \n工单已成功提交，编号为：**TK-20260526-0001**。  \n您可随时凭此编号查询处理进度。', 'BOM工具', '[\"http://36.150.236.251:9000/agent-demo/2026/02/28/a0e790c4b8d34a448dbd471f91bfe062.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/ae7ff1c437ac49aea3e05b88aebda394.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/afa8888118ce42fa9ff20081e09e6bfb.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/f5a79c72d0b1408682a1f6bb7cf48c30.png\",\"http://36.150.236.251:9000/agent-demo/2026/02/28/35db06d98dcd49d8856a2922f06692d4.png\",\"http://36.150.236.251:9000/agent-demo/2026/05/13/0b1f970b3c8649ee8b72959b1db8f719.png\"]', '[{\"featureName\":\"获取许可证失败报错\",\"chunkType\":\"FAQ\",\"score\":0.50458866},{\"featureName\":\"登录蓝U失败，提示已在其他电脑登录。\",\"chunkType\":\"FAQ\",\"score\":0.47002953},{\"featureName\":\"建模档合并订料\",\"chunkType\":\"caution\",\"score\":0.58888596},{\"featureName\":\"建模档合并订料\",\"chunkType\":\"prerequisite\",\"score\":0.5810188},{\"featureName\":\"建模档合并订料\",\"chunkType\":\"dependency\",\"score\":0.5797872}]', NULL, NULL, NULL, NULL, '2026-05-26 19:55:51', 1, 'a8c4443d4e4ce8f9986860faf24992ae', NULL, NULL);

SET FOREIGN_KEY_CHECKS = 1;
