package com.stocksage.agent.ordinary;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 普通路线计划中的一步：动作加上这一步已校验的取数参数。
 *
 * <p>证据验收按产生它的那一步参数核对周期、粒度和份数，不再共用一份请求参数。</p>
 */
public record PlanStep(PlanAction action, ReadRequest request) {

    public PlanStep {
        if (action == null) throw new IllegalArgumentException("Plan step requires an action");
        request = request == null ? ReadRequest.parse(PlanRoute.DIRECT, "", Map.of()) : request;
    }

    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("action", action.label());
        attributes.put("request", request.attributes());
        return attributes;
    }
}
