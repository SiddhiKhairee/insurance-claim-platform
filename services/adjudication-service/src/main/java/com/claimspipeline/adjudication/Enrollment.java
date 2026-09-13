package com.claimspipeline.adjudication;

import java.time.LocalDate;

public record Enrollment(
    String id, String employeeId, String employer, String planType, LocalDate effectiveDate, String status) {}
