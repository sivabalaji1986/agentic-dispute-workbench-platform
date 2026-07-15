package com.workbench.mcp;

import com.workbench.mcp.tools.CaseSystemTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CaseSystemMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseSystemMcpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider caseSystemToolCallbackProvider(CaseSystemTools caseSystemTools) {
        return MethodToolCallbackProvider.builder().toolObjects(caseSystemTools).build();
    }
}
