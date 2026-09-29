package com.stocksage.controller;

import com.stocksage.identity.RequestIdentity;
import com.stocksage.knowledge.ResearchMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户研究记忆的查看与撤销接口。
 *
 * <p>研究记忆来自已完成并通过门控的报告，用于后续查询的个性化上下文；用户可以查看来源，
 * 或将条目撤销。所有操作都通过 Session 用户 ID 隔离。</p>
 */
@RestController
@RequestMapping("/api/user/me/research-memory")
@RequiredArgsConstructor
public class ResearchMemoryController {

    /** 查询和软撤销研究记忆。 */
    private final ResearchMemoryService researchMemoryService;

    /** 从安全上下文解析当前用户。 */
    private final RequestIdentity requestIdentity;

    /**
     * 列出当前用户最近的有效研究记忆。
     *
     * @param limit 最大返回条数，服务层会再次限制范围
     * @return 脱敏后的研究记忆视图
     */
    @GetMapping
    public List<ResearchMemoryService.MemoryView> list(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return researchMemoryService.listForUser(requestIdentity.currentUserId(), limit);
    }

    /**
     * 软撤销一条属于当前用户的研究记忆。
     *
     * @param id 研究记忆数据库 ID
     * @return 撤销成功为 204，不存在或不属于当前用户为 404
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        return researchMemoryService.revoke(requestIdentity.currentUserId(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
