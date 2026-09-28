package com.claimspipeline.claimsintake;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAppealController.class)
@Import({SecurityConfig.class, AdminTokenConfig.class})
class AdminAppealControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private AdminAppealService service;

  @Test
  void documentIsStreamedAsAnAttachmentWithTheDetectedTypeAndNosniff() throws Exception {
    AppealDocument doc =
        new AppealDocument("doc-1", "appeals/claim-1/doc-1", "receipt.pdf", "application/pdf", 3);
    when(service.openDocument("claim-1", "doc-1"))
        .thenReturn(
            new AdminAppealService.DocumentDownload(doc, new ByteArrayInputStream(new byte[] {1, 2, 3})));

    mockMvc
        .perform(get("/api/admin/appeals/claim-1/documents/doc-1").with(admin()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(content().bytes(new byte[] {1, 2, 3}))
        .andExpect(header().string("Content-Length", "3"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"receipt.pdf\""));
  }

  @Test
  void decisionUsesTheTokenSubjectAsReviewer() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/appeals/claim-1/decision")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"UPHOLD\",\"note\":\"Not covered\"}"))
        .andExpect(status().isOk());

    verify(service).decide("claim-1", "UPHOLD", "Not covered", "admin.one");
  }

  @Test
  void documentDownloadNeedsTheAdminRole() throws Exception {
    mockMvc
        .perform(get("/api/admin/appeals/claim-1/documents/doc-1"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/api/admin/appeals/claim-1/documents/doc-1")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
        .andExpect(status().isForbidden());
    verify(service, never()).openDocument(any(), eq("doc-1"));
  }

  private static JwtRequestPostProcessor admin() {
    return jwt()
        .jwt(j -> j.subject("admin.one"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
