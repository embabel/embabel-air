package com.embabel.air.ai.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.rag.service.SearchOperations;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.backend.Customer;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Conversation;
import com.embabel.chat.UserMessage;
import com.embabel.springdata.EntityNavigationService;
import com.embabel.springdata.ToolFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The platform can use any action to respond to user messages.
 */
@EmbabelComponent
public class ChatActions {

    private final Logger logger = LoggerFactory.getLogger(ChatActions.class);

    private final ToolishRag airlinePolicies;
    private final AirProperties properties;

    private final ToolFacadeService toolFacadeService;
    private final EntityNavigationService entityNavigationService;

    public ChatActions(
            SearchOperations searchOperations,
            ToolFacadeService toolFacadeService,
            EntityNavigationService entityNavigationService,
            AirProperties properties) {
        this.airlinePolicies = new ToolishRag(
                "policies",
                "Embabel policies",
                searchOperations);
        this.toolFacadeService = toolFacadeService;
        this.entityNavigationService = entityNavigationService;
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
                .withReference(entityNavigationService.makeReference(customer, "customer"))
                .withTemplate("air")
                .respondWithSystemPrompt(conversation, Map.of(
                        "properties", properties
                ));
        context.sendMessage(conversation.addMessage(assistantMessage));
    }
}

class CustomerTools {

    private final Customer customer;

    public CustomerTools(Customer customer) {
        this.customer = customer;
    }

    @LlmTool
    public List<String> getReservations() {
        var reservations = customer.getReservations();
        return reservations.stream()
                .map(r -> "Reservation %s: %d flight segments, booked on %s".formatted(
                        r.getBookingReference(),
                        r.getFlightSegments().size(),
                        r.getCreatedAt().toString()
                ))
                .toList();
    }
}