package com.embabel.air.ai.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.rag.service.SearchOperations;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.backend.Customer;
import com.embabel.air.backend.Reservation;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Conversation;
import com.embabel.chat.UserMessage;
import com.embabel.springdata.EntityViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The platform can use any action to respond to user messages.
 */
@EmbabelComponent
public class ChatActions {

    private final Logger logger = LoggerFactory.getLogger(ChatActions.class);

    private final ToolishRag airlinePolicies;
    private final AirProperties properties;
    private final EntityViewService entityViewService;

    public ChatActions(
            SearchOperations searchOperations,
            EntityViewService entityViewService,
            AirProperties properties) {
        this.airlinePolicies = new ToolishRag(
                "policies",
                "Embabel policies",
                searchOperations);
        this.entityViewService = entityViewService;
        this.properties = properties;
    }

    /**
     * Bind Customer to AgentProcess. Will run once at the start of the process.
     */
    @Action
    Customer greetCustomer(
            Conversation conversation,
            ActionContext context) {
        var forUser = context.getProcessContext().getProcessOptions().getIdentities().getForUser();
        if (forUser instanceof Customer customer) {
            context.sendMessage(conversation.addMessage(
                    new AssistantMessage("Hi %s! How can I assist you today?".formatted(customer.getDisplayName()))));
            return customer;
        }

        logger.warn("bindUser: forUser is not an AirUser: {}", forUser);
        return null;
    }

    @Action(
            canRerun = true,
            trigger = UserMessage.class
    )
    void respond(
            Conversation conversation,
            Customer customer,
            ActionContext context) {
        var assistantMessage = context.
                ai()
                .withLlm(properties.chatLlm())
                .withId("ChatActions.respond")
                .withReference(airlinePolicies)
                .withReference(entityViewService.makeReference(customer))
                .withTool(entityViewService.finderFor(Reservation.class))
                .withTemplate("air")
                .respondWithSystemPrompt(conversation, Map.of(
                        "properties", properties
                ));
        context.sendMessage(conversation.addMessage(assistantMessage));
    }
}