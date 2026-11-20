package com.learnloop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="learnloop.material.parse.recovery-enabled=false")
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.learnloop.support.ExperimentClientTestSupport.class)
class LearnLoopApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	// 旧前端已移除，8080 只保留静态提示页；业务 API 仍必须登录。
	@Test
	void fallbackPageIsPublicButApiIsNot() throws Exception {
		mockMvc.perform(get("/")).andExpect(status().isOk());
		mockMvc.perform(get("/index.html")).andExpect(status().isOk());
		mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

}
