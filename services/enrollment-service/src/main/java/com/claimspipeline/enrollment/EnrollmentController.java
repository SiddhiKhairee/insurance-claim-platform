package com.claimspipeline.enrollment;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/enrollments")
public class EnrollmentController {

  private final EnrollmentRepository repository;

  public EnrollmentController(EnrollmentRepository repository) {
    this.repository = repository;
  }

  @PostMapping
  public ResponseEntity<Enrollment> create(@Valid @RequestBody Enrollment enrollment) {
    enrollment.setId(null);
    Enrollment saved = repository.save(enrollment);
    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
  }

  @GetMapping("/{employeeId}")
  public List<Enrollment> getByEmployeeId(@PathVariable String employeeId) {
    return repository.findByEmployeeId(employeeId);
  }
}
