package com.wzh.test;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.DescIndexResponseWrapper;
import io.milvus.response.QueryResultsWrapper;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Milvus 集合迁移工具
 * 将源服务器(A)的指定 collection 完整复制到目标服务器(B)
 * 包含:Schema 定义、索引、分区、所有数据
 */
public class MilvusCollectionMigrator {

    // ============== 在这里配置 ==============
    private static final String SOURCE_HOST = "10.82.12.51";   // 服务器 A 的 IP
    private static final int    SOURCE_PORT = 19530;
    private static final String SOURCE_TOKEN = "";              // 如果开启鉴权,填 username:password,否则留空

    private static final String TARGET_HOST = "10.82.13.61";   // 服务器 B 的 IP
    private static final int    TARGET_PORT = 19530;
    private static final String TARGET_TOKEN = "";

    private static final String COLLECTION_NAME = "feature_document_vectors";
    private static final int    BATCH_SIZE = 1000;              // 每批迁移的记录数
    private static final boolean DROP_TARGET_IF_EXISTS = false; // 目标已存在时是否删除重建
    // =======================================

    private final MilvusServiceClient sourceClient;
    private final MilvusServiceClient targetClient;

    public MilvusCollectionMigrator() {
        this.sourceClient = buildClient(SOURCE_HOST, SOURCE_PORT, SOURCE_TOKEN);
        this.targetClient = buildClient(TARGET_HOST, TARGET_PORT, TARGET_TOKEN);
    }

    private MilvusServiceClient buildClient(String host, int port, String token) {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withConnectTimeout(30, TimeUnit.SECONDS);
        if (token != null && !token.isEmpty()) {
            builder.withAuthorization(token.split(":")[0], token.split(":")[1]);
        }
        return new MilvusServiceClient(builder.build());
    }

    public void migrate() {
        try {
            log("==== 开始迁移 collection: " + COLLECTION_NAME + " ====");

            // 1. 检查源 collection 是否存在
            checkSourceExists();

            // 2. 获取源 collection 的 schema
            DescCollResponseWrapper sourceSchema = describeSourceCollection();

            // 3. 处理目标 collection
            handleTargetCollection(sourceSchema);

            // 4. 在目标端创建相同的 collection
            createTargetCollection(sourceSchema);

            // 5. 复制索引定义
            copyIndexes(sourceSchema);

            // 6. 加载源 collection 准备查询
            loadSourceCollection();

            // 7. 分批迁移数据
            migrateData(sourceSchema);

            // 8. 在目标端加载 collection
            loadTargetCollection();

            log("==== 迁移完成 ====");
        } catch (Exception e) {
            log("迁移失败: " + e.getMessage());
            throw new RuntimeException(e);
        } finally {
            sourceClient.close();
            targetClient.close();
        }
    }

    private void checkSourceExists() {
        R<Boolean> has = sourceClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(COLLECTION_NAME).build());
        if (has.getData() == null || !has.getData()) {
            throw new RuntimeException("源服务器不存在 collection: " + COLLECTION_NAME);
        }
        log("[1/8] 源 collection 存在性检查通过");
    }

    private DescCollResponseWrapper describeSourceCollection() {
        R<DescribeCollectionResponse> resp = sourceClient.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(COLLECTION_NAME).build());
        if (resp.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("获取源 schema 失败: " + resp.getMessage());
        }
        DescCollResponseWrapper wrapper = new DescCollResponseWrapper(resp.getData());
        log("[2/8] 获取源 schema 完成, 字段数: " + wrapper.getFields().size());
        return wrapper;
    }

    private void handleTargetCollection(DescCollResponseWrapper sourceSchema) {
        R<Boolean> has = targetClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(COLLECTION_NAME).build());
        boolean exists = has.getData() != null && has.getData();

        if (exists) {
            if (DROP_TARGET_IF_EXISTS) {
                targetClient.dropCollection(
                        DropCollectionParam.newBuilder().withCollectionName(COLLECTION_NAME).build());
                log("[3/8] 目标 collection 已存在,已删除");
            } else {
                throw new RuntimeException("目标 collection 已存在,请将 DROP_TARGET_IF_EXISTS 设为 true 或手动处理");
            }
        } else {
            log("[3/8] 目标 collection 不存在,准备创建");
        }
    }

    private void createTargetCollection(DescCollResponseWrapper sourceSchema) {
        List<FieldType> fieldTypes = new ArrayList<>();
        for (FieldType srcField : sourceSchema.getFields()) {
            // 直接复用源端的 FieldType
            fieldTypes.add(srcField);
        }

        CreateCollectionParam.Builder builder = CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION_NAME)
                .withDescription(sourceSchema.getCollectionDescription())
                .withShardsNum(sourceSchema.getShardNumber())
                .withFieldTypes(fieldTypes)
                .withEnableDynamicField(sourceSchema.getEnableDynamicField());

        R<RpcStatus> resp = targetClient.createCollection(builder.build());
        if (resp.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("创建目标 collection 失败: " + resp.getMessage());
        }
        log("[4/8] 目标 collection 创建完成");
    }

    private void copyIndexes(DescCollResponseWrapper sourceSchema) {
        R<DescribeIndexResponse> indexResp = sourceClient.describeIndex(
                DescribeIndexParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build());
        if (indexResp.getStatus() != R.Status.Success.getCode()) {
            log("[5/8] 源 collection 没有索引或获取失败,跳过索引复制: " + indexResp.getMessage());
            return;
        }

        DescIndexResponseWrapper wrapper = new DescIndexResponseWrapper(indexResp.getData());
        List<DescIndexResponseWrapper.IndexDesc> descs = wrapper.getIndexDescriptions();
        if (descs.isEmpty()) {
            log("[5/8] 源 collection 没有索引,跳过");
            return;
        }

        for (DescIndexResponseWrapper.IndexDesc desc : descs) {
            // 关键修复:extraParam 为空时必须传 "{}",否则服务端 JSON 解析失败
            String extraParam = desc.getExtraParam();
            if (extraParam == null || extraParam.trim().isEmpty()) {
                extraParam = "{}";
            }

            CreateIndexParam.Builder ib = CreateIndexParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withFieldName(desc.getFieldName())
                    .withIndexName(desc.getIndexName())
                    .withIndexType(desc.getIndexType())
                    .withMetricType(desc.getMetricType())
                    .withExtraParam(extraParam)
                    .withSyncMode(true);  // 同步等索引建好,后面 load 才不会报 index not found

            R<RpcStatus> cr = targetClient.createIndex(ib.build());
            if (cr.getStatus() != R.Status.Success.getCode()) {
                log("警告: 创建索引失败 field=" + desc.getFieldName() + ", msg=" + cr.getMessage());
            } else {
                log("    已复制索引: field=" + desc.getFieldName()
                        + ", type=" + desc.getIndexType()
                        + ", metric=" + desc.getMetricType()
                        + ", extraParam=" + extraParam);
            }
        }
        log("[5/8] 索引复制完成,共 " + descs.size() + " 个");
    }

    private void loadSourceCollection() {
        R<RpcStatus> resp = sourceClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withSyncLoad(true)
                        .build());
        if (resp.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("加载源 collection 失败: " + resp.getMessage());
        }
        log("[6/8] 源 collection 已加载");
    }

    private void migrateData(DescCollResponseWrapper sourceSchema) {
        // 找到主键字段名
        String pkFieldName = null;
        boolean autoId = false;
        List<String> outputFields = new ArrayList<>();
        for (FieldType f : sourceSchema.getFields()) {
            outputFields.add(f.getName());
            if (f.isPrimaryKey()) {
                pkFieldName = f.getName();
                autoId = f.isAutoID();
            }
        }
        if (pkFieldName == null) {
            throw new RuntimeException("未找到主键字段");
        }

        // 统计总数
        R<GetCollectionStatisticsResponse> stat = sourceClient.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build());
        long total = 0;
        if (stat.getStatus() == R.Status.Success.getCode()) {
            for (KeyValuePair kv : stat.getData().getStatsList()) {
                if ("row_count".equals(kv.getKey())) {
                    total = Long.parseLong(kv.getValue());
                }
            }
        }
        log("[7/8] 开始迁移数据,总计 " + total + " 行,批大小 " + BATCH_SIZE);

        long migrated = 0;
        Object lastPk = null;
        FieldType pkField = sourceSchema.getFieldByName(pkFieldName);
        boolean pkIsString = pkField.getDataType() == DataType.VarChar;

        while (true) {
            // 用主键做游标分页(比 offset 高效,适合大数据量)
            String expr;
            if (lastPk == null) {
                expr = pkIsString ? pkFieldName + " >= \"\"" : pkFieldName + " >= 0";
            } else {
                expr = pkIsString
                        ? pkFieldName + " > \"" + lastPk + "\""
                        : pkFieldName + " > " + lastPk;
            }

            R<QueryResults> qr = sourceClient.query(QueryParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withExpr(expr)
                    .withOutFields(outputFields)
                    .withLimit((long) BATCH_SIZE)
                    .build());

            if (qr.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("查询源数据失败: " + qr.getMessage());
            }

            QueryResultsWrapper qrw = new QueryResultsWrapper(qr.getData());
            List<QueryResultsWrapper.RowRecord> rows = qrw.getRowRecords();
            if (rows.isEmpty()) break;

            // 组装 InsertParam 的 fields
            List<InsertParam.Field> insertFields = buildInsertFields(sourceSchema, rows, autoId);

            R<MutationResult> ir = targetClient.insert(InsertParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withFields(insertFields)
                    .build());
            if (ir.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("写入目标失败: " + ir.getMessage());
            }

            migrated += rows.size();
            lastPk = rows.get(rows.size() - 1).get(pkFieldName);
            log(String.format("    已迁移 %d / %d  (%.1f%%)",
                    migrated, total, total == 0 ? 100.0 : migrated * 100.0 / total));

            if (rows.size() < BATCH_SIZE) break;
        }

        // 刷盘
        targetClient.flush(FlushParam.newBuilder()
                .addCollectionName(COLLECTION_NAME)
                .build());
        log("    数据迁移并刷盘完成,共 " + migrated + " 行");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<InsertParam.Field> buildInsertFields(
            DescCollResponseWrapper schema,
            List<QueryResultsWrapper.RowRecord> rows,
            boolean autoId) {

        List<InsertParam.Field> result = new ArrayList<>();
        for (FieldType ft : schema.getFields()) {
            // autoId 的主键由目标 Milvus 自己生成,不能传入
            if (autoId && ft.isPrimaryKey()) continue;

            List values = new ArrayList();
            for (QueryResultsWrapper.RowRecord row : rows) {
                Object v = row.get(ft.getName());
                values.add(v);
            }
            result.add(new InsertParam.Field(ft.getName(), values));
        }
        return result;
    }

    private void loadTargetCollection() {
        R<RpcStatus> resp = targetClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withSyncLoad(true)
                        .build());
        if (resp.getStatus() != R.Status.Success.getCode()) {
            log("警告: 加载目标 collection 失败: " + resp.getMessage());
        } else {
            log("[8/8] 目标 collection 已加载,可供查询");
        }
    }

    private static void log(String msg) {
        System.out.println("[" + new Date() + "] " + msg);
    }

    public static void main(String[] args) {
        new MilvusCollectionMigrator().migrate();
    }
}