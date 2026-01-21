package com.embabel.air.ai.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.annotation.State;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.tool.Tool;
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


    /**
     * Marker interface for process state
     */
    @State
    interface AirState {
    }

    /**
     * Bind Customer to AgentProcess and greet. Runs once at the start.
     * The hasRun_ condition automatically prevents this from running again
     * (since canRerun defaults to false).
     */
    @Action
    ChitchatState greetCustomer(
            Conversation conversation,
            ActionContext context,
            @Provided ToolishRag airlinePolicies) {
        var forUser = context.getProcessContext().getProcessOptions().getIdentities().getForUser();
        if (forUser instanceof Customer customer) {
            context.sendMessage(conversation.addMessage(
                    new AssistantMessage("Hi %s! How can I assist you today?".formatted(customer.getDisplayName()))));
            context.bindProtected("customer", customer);
        } else {
            logger.warn("greetCustomer: forUser is not a Customer: {}", forUser);
        }
        return new ChitchatState(airlinePolicies);
    }

    @State
    record ChitchatState(
            ToolishRag airlinePolicies
    ) implements AirState {

        @Action(
                trigger = UserMessage.class,
                canRerun = true
        )
        AirState respond(
                Conversation conversation,
                Customer customer,
                ActionContext context,
                @Provided AirProperties properties,
                @Provided EntityViewService entityViewService) {
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

    record ManageReservationState(
            ReservationView reservation
    ) implements AirState {

        @Action
        AirState note(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            context.sendAndSave(new AssistantMessage("I found your reservation: " + reservation.getDescription()));
            return this;
        }

        @Action(
                trigger = UserMessage.class,
                canRerun = true)
        AirState manage(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            context.sendMessage(conversation.addMessage(new AssistantMessage("Working on  your reservation: " + reservation.getDescription())));
            return this;
        }
    }
}