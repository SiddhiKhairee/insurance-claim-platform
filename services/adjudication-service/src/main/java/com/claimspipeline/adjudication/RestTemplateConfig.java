package com.claimspipeline.adjudication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

  private static final int TIMEOUT_MILLIS = 3000;

  @Bean
  public RestTemplate restTemplate() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(TIMEOUT_MILLIS);
    factory.setReadTimeout(TIMEOUT_MILLIS);
    return new RestTemplate(factory);
  }
}
