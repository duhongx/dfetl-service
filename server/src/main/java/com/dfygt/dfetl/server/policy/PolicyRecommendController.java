package com.dfygt.dfetl.server.policy;

import com.dfygt.dfetl.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * spec 054 - 同步策略推荐接口（不落库，纯计算）。
 */
@RestController
@RequestMapping("/api/sync-task/policy")
@RequiredArgsConstructor
public class PolicyRecommendController {

    private final PolicyRecommendService policyRecommendService;

    @PostMapping("/recommend")
    public ApiResponse<PolicyRecommendOutput> recommend(@RequestBody PolicyRecommendInput input) {
        return ApiResponse.ok(policyRecommendService.recommend(input));
    }
}
