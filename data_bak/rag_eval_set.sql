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

 Date: 07/05/2026 15:06:32
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for rag_eval_set
-- ----------------------------
DROP TABLE IF EXISTS `rag_eval_set`;
CREATE TABLE `rag_eval_set`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `category` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '分类: 问题类/使用方式类/功能介绍类',
  `sub_category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '功能模块,如 BOM工具、快速涂色',
  `query` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户问题',
  `expected_chunks` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '正确 chunk_id 列表,逗号分隔',
  `expected_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '参考答案(备查,不参与自动评估)',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 1=启用 0=禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE,
  INDEX `idx_enabled`(`enabled` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 25 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'RAG 评估集' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of rag_eval_set
-- ----------------------------
INSERT INTO `rag_eval_set` VALUES (1, '问题类', '赋注解属性工具', '弹出提示框:已执行某项操作,该操作可删除......此对话框即将关闭。', 'a9a3e4c9d1b44fca82705fc751ec1d55,d8d5ecb64d4c4efcba2a761d926ff008', '这个x不是报错。确定后要再次打开功能。', 1, '2026-05-05 20:20:08', '2026-05-05 20:20:08');
INSERT INTO `rag_eval_set` VALUES (2, '问题类', 'BOM工具', '使用BOM导出Excel的表格为什么行数是乱的?', '9f2d44ebd58a4f7e92295df3d5c5867e,42bcef0daafd45e6b35a3b6e46bdcd23,62003552fe774c20bb0fc7e326214d40,f9f4d8e46675475ca42c1a8390e8833c,51af564fd41e40d0bf77c03d6cf6f0e0,157f07d95d83410485233f70361de923', '导出EXCEL表的分页行数需要注意,不能超过原表格的最大行数,否则会超出行数乱码。', 1, '2026-05-05 20:20:08', '2026-05-07 11:16:00');
INSERT INTO `rag_eval_set` VALUES (3, '问题类', 'BOM工具', '为甚使用BOM导出TXT文本失败?', '9f2d44ebd58a4f7e92295df3d5c5867e,2d37ffe3b948458383f9f14ccf92d0a6,92cdb4a59ad74a70a175abfbf26168d1,f9f4d8e46675475ca42c1a8390e8833c,51af564fd41e40d0bf77c03d6cf6f0e0,157f07d95d83410485233f70361de923', '导出EXCEL表后才能转TXT文本。', 1, '2026-05-05 20:20:08', '2026-05-07 11:16:04');
INSERT INTO `rag_eval_set` VALUES (4, '问题类', '快速涂色赋注解', '我在使用快速涂色功能的时候,为什么上色功能失败?弹出个提示框,说是找不到配置表。', '87ae95067f27450390921191f9a0e5bb,b1e80d3af295496888b54cdffc6a4e63,43efc403982245e5a0b2e99f5fc09cc6,823f87edef194def96b832922b0c6cfe', '在进行上色之前要先选择设计标准。', 1, '2026-05-05 20:20:08', '2026-05-07 11:16:14');
INSERT INTO `rag_eval_set` VALUES (5, '问题类', '建模档合并订料', '使用建模档合并订料功能,弹出备料板重量超出限定值的的提示。要怎么改?', '5de83f2827b34ee19283077097a157e8,60b0ba342b3b47d3acb5170449ca19cb,1b74bdb1e2154a0da5fec711b972db30,987edbd6d7864bdb895f0d6d3e29b373,b701e8e2d4ec454c9473f1de6ab700f3', '对备料板的重量重新进行估算,使实际重量小于预估重量。', 1, '2026-05-05 20:20:08', '2026-05-07 11:16:27');
INSERT INTO `rag_eval_set` VALUES (6, '问题类', '赋属性工具', '赋属性工具中编辑原点的功能有什么作用?', '50dcbbb2c27b4f628560e63dd68548c0,9a7e2969e4eb40fc96e12ff5fc30db09,12e79b27dd844b00817b647135202f0e,3eb7858e33934e2683bc8a0a825c1c52,785df2b7b57a4d07b6e0c78b58936da8,253d5a3644904421b4286e9a4aabbbcb', '用于检查、修改批量/单个工件原点。', 1, '2026-05-05 20:20:08', '2026-05-07 11:16:39');
INSERT INTO `rag_eval_set` VALUES (7, '问题类', '赋属性工具', '出图的前置条件是什么?', '253d5a3644904421b4286e9a4aabbbcb,63ecf17a64204c0dadb39a3f224aa67a,ea4382fd546142a2bb9a413ea1ab89ab,3bd93e900b744b31b510142a65e5c2c3,50dcbbb2c27b4f628560e63dd68548c0,785df2b7b57a4d07b6e0c78b58936da8,b2d48485d93944c19b663d78924208f2', '完成赋属性工具的相关操作,生成建模档实体的零件编号、长宽高、材质、热处理、重量、页码属性。', 1, '2026-05-05 20:20:08', '2026-05-07 11:16:59');
INSERT INTO `rag_eval_set` VALUES (8, '问题类', '赋注解属性工具', '为什么导出的图没有注解?', '531cc62c67eb4b2a8f3837d3f938af84,64eb8f17a3514f9ca26e79df28496b2c,5866cf59bb254886a7663ed3bc54a7ac,a77b09cfcfdd4665ad10dd93ee81069d', '需要在赋注解属性工具中进行自动分析注解,这是导图的前置工作条件,必须分析注解后导图才有注解。', 1, '2026-05-05 20:20:08', '2026-05-07 11:17:10');
INSERT INTO `rag_eval_set` VALUES (9, '问题类', '建模档合并订料', '备料板尺寸的大小是否会随前距、后距、左距、右距的值变化而产生变化?', 'dae60457df664d4999699456713f4970,aea6e20f5bc942679d6aad35ecb48bef,5f330f5feffd45589a9b36eeefdcb6e8,7f47789dc4204943a1a8798ae9346ba5,4a9539b2f0e14e2e85c291e37c7a8a7c', '是,备料板尺寸的大小会随前距、后距、左距、右距的值变化而变化。', 1, '2026-05-05 20:20:08', '2026-05-07 11:17:21');
INSERT INTO `rag_eval_set` VALUES (10, '使用方式类', '赋属性工具', '如何使用赋属性这个功能?', '785df2b7b57a4d07b6e0c78b58936da8,3eb7858e33934e2683bc8a0a825c1c52,6034719f15644a88a665171c298bd009,bde6cce5f4a34832a4a7cd8047279e69,3bd93e900b744b31b510142a65e5c2c3,be9a23c02b544f6289465c24ca5381f0,effa4be7f80f4140ad6034647bd104d2', '步骤一选择写入属性以及客户标准...步骤五原点方向规则。', 1, '2026-05-05 20:20:08', '2026-05-07 11:17:40');
INSERT INTO `rag_eval_set` VALUES (11, '使用方式类', 'BOM工具', '如何使用BOM表工具这个功能?', '51af564fd41e40d0bf77c03d6cf6f0e0,157f07d95d83410485233f70361de923,100127ad10d3477ab013550b229a140a,d500a3a9f07c41d6a98d5bd930771b4d,60cebded986b42b2972b77cd9e0729c2,559a6114242e4e5a80e3a3d075affbd5', '步骤一填写并检查客户信息...步骤五点击导出EXCEL。', 1, '2026-05-05 20:20:08', '2026-05-07 11:17:51');
INSERT INTO `rag_eval_set` VALUES (12, '使用方式类', '建模出图工具', '如何使用建模出图工具这个功能?', 'bd2614db48074c9e9a420af8bedd404a,c3562f9d5ffd40f18c73d7b649b86b1d,45b9f9a42b5e4c489431be08f38ba16d,c97b18e9aaf741f2b5b103464fbbe037,0edb3e031d8e4d29b096c93a712a27ba', '步骤一填写模具编号...步骤八打开图纸路径查看图纸。', 1, '2026-05-05 20:20:08', '2026-05-07 11:18:05');
INSERT INTO `rag_eval_set` VALUES (13, '使用方式类', '快速涂色赋注解', '如何使用快速涂色赋注解这个功能?', 'bdf19b9e23d44f03ae525db7eddf6ae7,f7c0bd528dd34e18b30029c0081cc7e3,c7f438ce12c9404c84b8b36166b18406,fc744951444244acae83833a1cf13f1e,e1728d94e1ee4e80a8adec757de401df,d835745fbea64757a0e17f9dff654854,138bb0fa845c435d9108fe89ebf31c2f,2d72b89bea7b44428dbeb5200b07aaf1', '步骤一选择设计标准...步骤四修改、添加注解。', 1, '2026-05-05 20:20:08', '2026-05-07 11:18:19');
INSERT INTO `rag_eval_set` VALUES (14, '使用方式类', '赋属性工具', '赋属性工具中编辑原点的功能如何使用?', '50dcbbb2c27b4f628560e63dd68548c0,12e79b27dd844b00817b647135202f0e,9a7e2969e4eb40fc96e12ff5fc30db09,3eb7858e33934e2683bc8a0a825c1c52,d46fc9c1bd8e4f3fa660e0aa27dfd5c2,320bea8ca5be4957825a3de16816d5dc,785df2b7b57a4d07b6e0c78b58936da8,253d5a3644904421b4286e9a4aabbbcb', '先勾选检查原点,把所有坯料框显示。再选择单个或多个工件修改原点位置,应用更新,确定。', 1, '2026-05-05 20:20:08', '2026-05-07 11:18:35');
INSERT INTO `rag_eval_set` VALUES (15, '使用方式类', '建模档合并订料', '如何使用建模档合并订料的删除实体功能?', '0a60d10491e04ef48f0ba427b033a690,a852896648064cf79b97522ebdf1ef8f,d0cc44d66f184156acd53b2226006539,d0d0ec1b439e48b9a9a8c9ca8cb1cdd7,f3a6340fb5004f6a8bc6acdc5ddadf35', '单击\"删除实体\",弹出\"选择需要移除的实体\"UI对话框;选择删除的对象。', 1, '2026-05-05 20:20:08', '2026-05-05 20:20:08');
INSERT INTO `rag_eval_set` VALUES (16, '使用方式类', '快速涂色', '快速涂色功能具体如何使用?', 'cffefe3a04ba46c9b250d22f56498d63,0ebd2cadc49542d2aced9843bc251535,9d78a9b72c744051aa17743d06f8e997,d89e9f0818a741cb924c0dc04023eefa,7d0d1545b9534eeb9832cbff46c58b38,eb12138f2c784eac8b723f451e7f2d19,0f0d4d2845784cbda648300c9df1b703,1c67fc0626f44cde8696e5d19ad1a0b4,1cf021d2727046db8c9edbd698c52ff1,3a38e3d5df09457e834ff3470ab92349,524f17c33849410b83994c69ffdb8a9d,8195e92a6d244f4595c48a4b38094733,9dd656d0445c41a281417a0d487a2e0b,c7d807a9f1764aa5ae61cff8c94ad592', '步骤一选择设计标准...步骤四上色。', 1, '2026-05-05 20:20:08', '2026-05-07 11:19:04');
INSERT INTO `rag_eval_set` VALUES (17, '功能介绍类', '建模出图工具', '建模出图工具是做什么用的?', '45b9f9a42b5e4c489431be08f38ba16d,0edb3e031d8e4d29b096c93a712a27ba,bd2614db48074c9e9a420af8bedd404a,0eb31ee8f81f4c139338200ddf043529', '可用于建模档3D导出2D图。', 1, '2026-05-05 20:20:08', '2026-05-07 11:19:18');
INSERT INTO `rag_eval_set` VALUES (18, '功能介绍类', '零件刻字', '零件刻字这个功能主要用户做什么?', '9207104d1e4c4b0bbf7815f6c65380b6', '用于建模档排序赋属性后的带属性刻字。', 1, '2026-05-05 20:20:08', '2026-05-05 20:20:08');
INSERT INTO `rag_eval_set` VALUES (19, '功能介绍类', '零件刻字', '零刻刻字功能,有几种刻字方式?', '0637e210b53f44468a85344881fd6b4d,9e5b0014b63d4d5f8834f51fdf25e842,ebaa422dc73243b79324d0d78b1d5a7d,5ce58f3334f54773ba2bf75f72dee94f,2e4f07586a544c428cd5666e172e2255', '分割面刻字、凹槽刻字和凸起刻字。', 1, '2026-05-05 20:20:08', '2026-05-07 11:19:34');
INSERT INTO `rag_eval_set` VALUES (20, '功能介绍类', '快速涂色', '快速涂色这个功能主要用户做什么?', 'feec674af49b4c3f8cf2412d2175e676,9d78a9b72c744051aa17743d06f8e997,d89e9f0818a741cb924c0dc04023eefa,3c14caf284c44115be193955dd773723,0f0d4d2845784cbda648300c9df1b703,eb12138f2c784eac8b723f451e7f2d19,9dd656d0445c41a281417a0d487a2e0b,1cf021d2727046db8c9edbd698c52ff1,3a38e3d5df09457e834ff3470ab92349,597d6d10a81941959ad3240c363b67cf,70500b7488664f2fa853d7c40ad7a544,8195e92a6d244f4595c48a4b38094733', '用于建模档体、孔、键槽、面的批量切换颜色。', 1, '2026-05-05 20:20:08', '2026-05-07 11:19:50');
INSERT INTO `rag_eval_set` VALUES (21, '功能介绍类', '出图预设', '建模档出图预设功能需要填写哪些公共信息?', '507cac6e4f884806a6d5364011db4575,fcee7f2535f841b6b7f8bd7af0c2be4e,7166bb36b68d45f7b11cb0e9535b4f8c,50d7712c54ea43de905c456af9a4c5b9,00ca4fb8d7134651a1a797461ce98601,24441a5854c444cb859ff55e033d0b52', '用户标准、客户信息、标准园入子品牌、产品编号、产品厚度、模具变化、模具名称、版本号。', 1, '2026-05-05 20:20:08', '2026-05-07 11:20:03');
INSERT INTO `rag_eval_set` VALUES (22, '功能介绍类', '赋属性工具', '介绍一下赋属性工具这个功能的作用。', '253d5a3644904421b4286e9a4aabbbcb,be9a23c02b544f6289465c24ca5381f0,3bd93e900b744b31b510142a65e5c2c3,b2d48485d93944c19b663d78924208f2,785df2b7b57a4d07b6e0c78b58936da8,3eb7858e33934e2683bc8a0a825c1c52,effa4be7f80f4140ad6034647bd104d2,f6bc83a5fdf9479f91496563045836e0', '可用于生成建模档实体的零件编号、长宽高、材质、热处理、重量、页码属性,为出图的前置条件。', 1, '2026-05-05 20:20:08', '2026-05-07 11:20:17');
INSERT INTO `rag_eval_set` VALUES (23, '功能介绍类', '赋注解属性工具', '介绍一下赋注解属性这个功能的作用。', 'a491fad9f61542a190af30ec9272aca6,7d43132f97e84648b0485c3189bdf650,e8df7e9ce3d64185a8d1c23276475d3d,d8d5ecb64d4c4efcba2a761d926ff008', '用于UG建模档分析预览零件注解以及修改注解。', 1, '2026-05-05 20:20:08', '2026-05-07 11:20:27');
INSERT INTO `rag_eval_set` VALUES (24, '功能介绍类', '建模档合并订料', '建模档合并订料的旋转功能,有几种旋转方式?', 'c25b71f92d6c4a93a2adde2ea236e434,1b79cd4bbb014f7f81edf89f91ea0648,1dcb8de582574b1e870442dd028a8feb,4fee2c104e014ad7993d685a917030f6,556966a5a08d47198cc4358c859af468,617a0cbe1a254001904debd41787cf52,b701e8e2d4ec454c9473f1de6ab700f3', '四种。左旋90度,右旋90度,左右反转,上下反转。', 1, '2026-05-05 20:20:08', '2026-05-07 11:20:40');

SET FOREIGN_KEY_CHECKS = 1;
