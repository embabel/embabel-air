package com.embabel.air.ai.config;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.Verbosity;
import com.embabel.chat.Chatbot;
import com.embabel.chat.agent.AgentProcessChatbot;
import com.embabel.springdata.EntityTools;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Configure a chatbot that uses all actions available on the AgentPlatform
 */
@Configuration
class ChatConfiguration {

    @Bean
    Chatbot chatbot(AgentPlatform agentPlatform) {
        return AgentProcessChatbot.utilityFromPlatform(
                agentPlatform,
                new Verbosity().showPrompts()
        );
    }

    @Bean
    EntityTools entityTools(
            TransactionTemplate transactionTemplate,
            ListableBeanFactory listableBeanFactory
    ) {
        return new EntityTools(transactionTemplate, listableBeanFactory);
    }
}
