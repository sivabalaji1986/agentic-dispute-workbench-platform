package com.workbench.caseagent;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class CaseReviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseReviewAgentApplication.class, args);
    }

    @Bean
    public AgentCard agentCard(@Value("${server.port:8081}") int port) {
        return new AgentCard.Builder()
                .name("case-review-agent")
                .description("Reviews operational case facts: transaction, merchant response, "
                        + "and available evidence documents.")
                .url("http://localhost:" + port + "/a2a")
                .version("1.0.0")
                .capabilities(new AgentCapabilities.Builder().streaming(false).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("case_review")
                        .name("Review case facts")
                        .description("Checks transaction status, merchant response, and available "
                                + "evidence documents for a dispute case")
                        .tags(List.of("case-review"))
                        .examples(List.of("Check transaction, merchant response, case status and "
                                + "available evidence for dispute case D-10291, dispute type "
                                + "GOODS_NOT_RECEIVED."))
                        .build()))
                .protocolVersion("0.3.0")
                .build();
    }

    // @Bean AgentExecutor is added in Task 3, once CaseReviewAgentExecutor exists.
}
