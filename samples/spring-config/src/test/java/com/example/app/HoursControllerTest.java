package com.example.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@ShopTest
class HoursControllerTest {
  @Autowired MockMvc mvc;

  @Test
  void saysItsOpenAtTen() throws Exception {
    mvc.perform(get("/open")).andExpect(content().string("open"));
  }
}
