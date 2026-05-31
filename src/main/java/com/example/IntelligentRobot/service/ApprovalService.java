package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.client.FeishuClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 审批事件处理服务
 * 处理飞书审批实例状态变化事件
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    @Value("${feishu.approval-definition-code:}")
    private String approvalDefinitionCode;

    private final FeishuClient feishuClient;

    public ApprovalService(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    /**
     * 处理审批实例状态变化事件
     * 事件类型：approval.instance.state_change_v4
     *
     * @param eventData 飞书事件数据中的 event 字段
     */
    @SuppressWarnings("unchecked")
    public void handleApprovalStateChange(Map<String, Object> eventData) {
        try {
            // 打印完整事件数据，方便调试
            log.info("收到审批事件完整数据: {}", eventData);

            String approvalCode = (String) eventData.get("approval_code");
            String instanceCode = (String) eventData.get("instance_code");
            String state = (String) eventData.get("state"); // "APPROVED" / "REJECTED" / "PENDING"
            String openId = (String) eventData.get("open_id");  // 申请人 open_id

            log.info("收到审批状态变化: approvalCode={}, instanceCode={}, state={}, openId={}",
                    approvalCode, instanceCode, state, openId);

            // 只处理通过/拒绝的最终状态
            if (!"APPROVED".equalsIgnoreCase(state) && !"REJECTED".equalsIgnoreCase(state)) {
                log.info("审批状态非终态，跳过: {}", state);
                return;
            }

            // 获取审批详情（审批名称、审批意见等）
            ApprovalDetail detail = fetchApprovalDetail(instanceCode, approvalCode);

            // 发送给申请人
            if (openId != null && !openId.isEmpty()) {
                String card = FeishuClient.buildApprovalCard(
                        detail.approvalName,
                        detail.applicantName,
                        state,
                        detail.comment,
                        detail.detailUrl
                );
                feishuClient.sendCard(openId, card);
                log.info("审批结果卡片已发送给申请人: {}", openId);
            }

            // 发送给所有审批人（从事件数据中获取）
            Object approversObj = eventData.get("approvers");
            if (approversObj instanceof java.util.List<?> approvers) {
                for (Object approverObj : approvers) {
                    String approverOpenId = null;
                    if (approverObj instanceof String s) {
                        approverOpenId = s;
                    } else if (approverObj instanceof Map<?, ?> m) {
                        approverOpenId = (String) m.get("open_id");
                    }

                    if (approverOpenId != null && !approverOpenId.isEmpty() && !approverOpenId.equals(openId)) {
                        String card = FeishuClient.buildApprovalCard(
                                detail.approvalName,
                                detail.applicantName,
                                state,
                                detail.comment,
                                detail.detailUrl
                        );
                        feishuClient.sendCard(approverOpenId, card);
                        log.info("审批结果卡片已发送给审批人: {}", approverOpenId);
                    }
                }
            }

        } catch (Exception e) {
            log.error("处理审批状态变化事件失败", e);
        }
    }

    /**
     * 获取审批实例详情
     * API: GET /approval/v4/instances/{instance_id}?approval_code=xxx&open_id=xxx
     */
    @SuppressWarnings("unchecked")
    private ApprovalDetail fetchApprovalDetail(String instanceCode, String approvalCode) {
        ApprovalDetail detail = new ApprovalDetail();
        detail.approvalName = "审批申请";
        detail.applicantName = "申请人";
        detail.comment = "无";
        detail.detailUrl = "https://www.feishu.cn/approval-center/";

        try {
            String url = feishuClient.getApiBaseUrl()
                    + "/approval/v4/instances/" + instanceCode
                    + "?approval_code=" + approvalCode;
            String responseBody = feishuClient.getWithToken(url);
            log.info("获取审批详情响应: {}", responseBody);

            Map<String, Object> result = new com.fasterxml.jackson.databind.ObjectMapper().readValue(responseBody, Map.class);
            if (!"0".equals(String.valueOf(result.get("code")))) {
                log.warn("获取审批详情失败: code={}, msg={}", result.get("code"), result.get("msg"));
                return detail;
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) return detail;

            Map<String, Object> instance = (Map<String, Object>) data.get("instance");
            if (instance == null) return detail;

            detail.approvalName = (String) instance.getOrDefault("approval_name", "审批申请");
            detail.applicantName = (String) instance.getOrDefault("applicant_name", "申请人");

            // 获取最新审批意见
            Object tasksObj = instance.get("tasks");
            if (tasksObj instanceof java.util.List<?> tasks && !tasks.isEmpty()) {
                Map<String, Object> lastTask = (Map<String, Object>) tasks.get(tasks.size() - 1);
                detail.comment = (String) lastTask.getOrDefault("comment", "无");
            }

        } catch (Exception e) {
            log.warn("获取审批详情失败: {}", instanceCode, e);
        }
        return detail;
    }

    /**
     * 审批详情数据
     */
    private static class ApprovalDetail {
        String approvalName;
        String applicantName;
        String comment;
        String detailUrl;
    }
}
