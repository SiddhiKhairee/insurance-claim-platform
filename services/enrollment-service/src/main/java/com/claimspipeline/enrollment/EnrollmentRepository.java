package com.claimspipeline.enrollment;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EnrollmentRepository extends MongoRepository<Enrollment, String> {

  List<Enrollment> findByEmployeeId(String employeeId);
}
