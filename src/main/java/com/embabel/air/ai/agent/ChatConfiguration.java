package com.embabel.air.ai.agent;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.Verbosity;
import com.embabel.air.ai.AirProperties;
import com.embabel.chat.Chatbot;
import com.embabel.chat.agent.AgentProcessChatbot;
import com.embabel.chat.support.InMemoryConversationFactory;
import com.embabel.common.textio.template.TemplateRenderer;
import com.embabel.springdata.EntityViewService;
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
    Chatbot chatbot(AgentPlatform agentPlatform, AirProperties properties) {
        return AgentProcessChatbot.utilityFromPlatform(
                agentPlatform,
                new InMemoryConversationFactory(),
                new Verbosity().withShowPrompts(properties.showChatPrompts())
        );
    }

    @Bean
    EntityViewService entityViewService(
            TransactionTemplate transactionTemplate,
            ListableBeanFactory listableBeanFactory,
            TemplateRenderer templateRenderer
    ) {
        return new EntityViewService(
                transactionTemplate,
                listableBeanFactory,
                templateRenderer,
                "com.embabel.air"
        );
    }
}
