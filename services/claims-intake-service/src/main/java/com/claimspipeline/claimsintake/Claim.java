package com.claimspipeline.claimsintake;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "claims")
public class Claim {

  @Id private String id;

  private String claimId;
  private String employeeId;
  private String planType;
  private BigDecimal amountRequested;
  private String description;
  private String status;
  private Instant submittedAt;
  private Instant adjudicatedAt;
  private String decisionReason;
  private List<String> ruleTrace;

  public Claim() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getClaimId() {
    return claimId;
  }

  public void setClaimId(String claimId) {
    this.claimId = claimId;
  }

  public String getEmployeeId() {
    return employeeId;
  }

  public void setEmployeeId(String employeeId) {
    this.employeeId = employeeId;
  }

  public String getPlanType() {
    return planType;
  }

  public void setPlanType(String planType) {
    this.planType = planType;
  }

  public BigDecimal getAmountRequested() {
    return amountRequested;
  }

  public void setAmountRequested(BigDecimal amountRequested) {
    this.amountRequested = amountRequested;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(Instant submittedAt) {
    this.submittedAt = submittedAt;
  }

  public Instant getAdjudicatedAt() {
    return adjudicatedAt;
  }

  public void setAdjudicatedAt(Instant adjudicatedAt) {
    this.adjudicatedAt = adjudicatedAt;
  }

  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(String decisionReason) {
    this.decisionReason = decisionReason;
  }

  public List<String> getRuleTrace() {
    return ruleTrace;
  }

  public void setRuleTrace(List<String> ruleTrace) {
    this.ruleTrace = ruleTrace;
  }
}
