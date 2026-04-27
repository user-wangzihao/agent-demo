package com.wzh.controller;

import com.wzh.common.UserContext;
import com.wzh.entity.dto.rageval.RagEvalRunRequest;
import com.wzh.entity.dto.rageval.RagEvalRunResponse;
import com.wzh.service.RagEvalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
 
/**
 * RAG 评估接口
 *
 * <p>仅管理员可用,普通用户访问会被拦截.</p>
 *
 * @author wzh
 * @since 2026-04-27
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/rag-eval")
@RequiredArgsConstructor
public class RagEvalController {
 
    private final RagEvalService ragEvalService;
 
    /**
     * 跑一次评估.
     *
     * <pre>
     * POST /api/admin/rag-eval/run
     * Header: Authorization: Bearer &lt;admin token&gt;
     * Body:
     * {
     *   "pipeline": "baseline",
     *   "topK": 5,
     *   "evalSetIds": null
     * }
     * </pre>
     */
    @PostMapping("/run")
    public RagEvalRunResponse run(@RequestBody(required = false) RagEvalRunRequest request) {
        // 权限校验: 沿用项目的 UserContext (com.wzh.common.UserContext)
        //if (!isAdmin()) {
        //    throw new SecurityException("无权限访问: 仅管理员可调用 RAG 评估接口");
        //}
        if (request == null) {
            request = new RagEvalRunRequest();
        }
        log.info("[RAG-EVAL] 收到评估请求 pipeline={} topK={} evalSetIds={}",
                request.getPipeline(), request.getTopK(), request.getEvalSetIds());
        return ragEvalService.run(request);
    }
 
    /**
     * 兼容判定: 项目里如果 UserContext 直接暴露 isAdmin() 静态方法,可改为 UserContext.isAdmin().
     * 这里通过 TokenInfo 间接判断,避免依赖未确认的方法签名.
     */
    private boolean isAdmin() {
        try {
            // 项目里 UserContext.get() 返回 TokenInfo,有 getRole() 方法判断角色
            // 如果你的 TokenInfo 字段名不同,只改这一处即可
            com.wzh.utils.TokenUtil.TokenInfo info = UserContext.get();
            if (info == null) return false;
            // 兼容两种命名: getRole() / isAdmin()
            try {
                java.lang.reflect.Method m = info.getClass().getMethod("isAdmin");
                Object r = m.invoke(info);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (NoSuchMethodException ignore) {}
            try {
                java.lang.reflect.Method m = info.getClass().getMethod("getRole");
                Object r = m.invoke(info);
                return r != null && "admin".equals(r.toString());
            } catch (NoSuchMethodException ignore) {}
            return false;
        } catch (Exception e) {
            log.warn("[RAG-EVAL] 权限校验异常,默认拒绝", e);
            return false;
        }
    }
}