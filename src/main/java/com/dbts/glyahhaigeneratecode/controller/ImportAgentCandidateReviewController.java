package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.projectimport.ImportAgentCandidateReviewService;
import com.dbts.glyahhaigeneratecode.projectimport.ImportAgentCandidateReviewRepository.Review;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/project/import/agent-edits")
@RequiredArgsConstructor
public class ImportAgentCandidateReviewController {
    private final ImportAgentCandidateReviewService reviews;
    private final UserService users;

    @GetMapping("/{editId}/review")
    public BaseResponse<Review> current(@PathVariable UUID editId, HttpServletRequest request) {
        return ResultUtils.success(reviews.current(users.getUserInSession(request).getId(), editId));
    }

    @PostMapping("/{editId}/review/decision")
    public BaseResponse<Review> decide(@PathVariable UUID editId,
                                       @RequestBody ImportAgentCandidateReviewService.DecisionDTO input,
                                       HttpServletRequest request) {
        return ResultUtils.success(reviews.decide(users.getUserInSession(request).getId(), editId, input,
                request.getSession(false).getId()));
    }
}
