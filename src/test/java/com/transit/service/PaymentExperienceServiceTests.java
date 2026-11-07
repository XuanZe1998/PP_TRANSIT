package com.transit.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PaymentExperienceServiceTests {
 @Test void rejectsUnsafeOriginAndHeaderInjection() {
  var settings=mock(AdminReportService.class);var service=new PaymentExperienceService(mock(JdbcTemplate.class),new ObjectMapper(),settings);
  var copy=service.defaults();copy.put("websiteUrl","javascript:alert(1)");assertThatThrownBy(()->service.save(copy)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  copy.put("websiteUrl","https://example.com");copy.put("emailSubject","test\r\nBcc: other@example.com");assertThatThrownBy(()->service.save(copy)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(settings);
 }
 @Test void acceptsCompleteContentAndNormalizesOrigin() {
  var service=new PaymentExperienceService(mock(JdbcTemplate.class),new ObjectMapper(),mock(AdminReportService.class));
  var copy=service.defaults();copy.put("websiteUrl","https://example.com/");copy.put("qrNotice","Custom notice");assertThat(service.save(copy)).containsEntry("websiteUrl","https://example.com").containsEntry("qrNotice","Custom notice");
 }
}
