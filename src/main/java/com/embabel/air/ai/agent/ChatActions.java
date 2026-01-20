package com.embabel.air.ai.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.rag.service.SearchOperations;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.ai.view.ReservationView;
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

    @State
    interface AirState {
        // marker interface for process state
    }

    /**
     * Bind Customer to AgentProcess and greet. Runs once at the start.
     * The hasRun_ condition automatically prevents this from running again
     * (since canRerun defaults to false).
     */
    @Action
    ChitchatState greetCustomer(
            Conversation conversation,
            ActionContext context) {
        var forUser = context.getProcessContext().getProcessOptions().getIdentities().getForUser();
        if (forUser instanceof Customer customer) {
            context.sendMessage(conversation.addMessage(
                    new AssistantMessage("Hi %s! How can I assist you today?".formatted(customer.getDisplayName()))));
            context.bindProtected("customer", customer);
        } else {
            logger.warn("greetCustomer: forUser is not a Customer: {}", forUser);
        }
        return new ChitchatState(properties, airlinePolicies, entityViewService);
    }

    @State
    static class ChitchatState implements AirState {

        private final AirProperties properties;
        private final ToolishRag airlinePolicies;
        private final EntityViewService entityViewService;

        ChitchatState(AirProperties properties, ToolishRag airlinePolicies, EntityViewService entityViewService) {
            this.properties = properties;
            this.airlinePolicies = airlinePolicies;
            this.entityViewService = entityViewService;
        }

        @Action(
                trigger = UserMessage.class,
                canRerun = true
        )
        AirState respond(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            var assistantMessage = context.
                    ai()
                    .withLlm(properties.chatLlm())
                    .withId("chitchat.respond")
                    .withReference(airlinePolicies)
                    .withReference(entityViewService.viewOf(customer))
                    .withTool(
                            Tool.replanAndAdd(
                                    entityViewService.finderFor(Reservation.class),
                                    ManageReservationState::new
                            ))
                    .withTemplate("air")
                    .respondWithSystemPrompt(
                            conversation,
                            Map.of(
                                    "properties", properties
                            ));
            context.sendAndSave(assistantMessage);
            return this;
        }
    }

    static class ManageReservationState implements AirState {

        private final ReservationView reservation;

        public ManageReservationState(ReservationView reservation) {
            this.reservation = reservation;
        }

        // TODO need exit tool

        @Action
        AirState init(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            context.sendAndSave(new AssistantMessage("I found your reservation: " + reservation.getDescription()));
            return this;
        }


        @Action(
                trigger = UserMessage.class,
                canRerun = true)
        AirState respond(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            context.sendMessage(conversation.addMessage(new AssistantMessage("Working on  your reservation: " + reservation.getDescription())));
            return this;
        }
    }
}