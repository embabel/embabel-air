package com.embabel.air.ai.agent;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.tool.Tool;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.ai.rag.RagConfiguration.AirlinePolicies;
import com.embabel.air.ai.view.ReservationView;
import com.embabel.air.backend.Customer;
import com.embabel.air.backend.Reservation;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Conversation;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.springdata.EntityViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The platform can use any action to respond to user messages.
 */
@EmbabelComponent
public class ChatActions {

    private final static Logger logger = LoggerFactory.getLogger(ChatActions.class);


    /**
     * Condition: true if the last message in the conversation is from the user,
     * meaning we need to respond.
     */
    @Condition
    static boolean shouldRespond(Conversation conversation) {
        return conversation.lastMessageIfBeFromUser() != null;
    }

    /**
     * Marker interface for process state
     */
    @State
    interface AirState {
    }

    /**
     * A substate performs triage to determine if we are still on topic
     * before doing anything else.
     */
    interface SubState extends AirState {

        String purpose();

        record OnTopic(
                boolean isOnTopic,
                String reason) {
        }

        /**
         * Respond method to be implemented by each SubState.
         */
        AirState respond(
                Conversation conversation,
                Customer customer,
                ActionContext context,
                AirProperties properties,
                AirlinePolicies airlinePolicies,
                EntityViewService entityViewService);

        /**
         * Triage action: check if we are still on topic before responding.
         * If on-topic, delegates to respond(). If off-topic, transitions to ChitchatState.
         */
        @Action(
                pre = "shouldRespond",
                canRerun = true
        )
        default AirState triage(
                Conversation conversation,
                Customer customer,
                ActionContext context,
                @Provided AirProperties properties,
                @Provided AirlinePolicies airlinePolicies,
                @Provided EntityViewService entityViewService) {
            var onTopic = context.
                    ai()
                    .withAutoLlm()
                    .creating(OnTopic.class)
                    .fromMessages(
                            io.vavr.collection.List.<Message>of(new SystemMessage("""
                                            Are we still on topic with the purpose of '%s'?
                                            """.formatted(purpose())))
                                    .appendAll(conversation.getMessages())
                                    .asJava());
            if (onTopic.isOnTopic()) {
                return respond(conversation, customer, context, properties, airlinePolicies, entityViewService);
            }
            return new ChitchatState();
        }

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
            @Provided AirlinePolicies airlinePolicies) {
        var forUser = context.getProcessContext().getProcessOptions().getIdentities().getForUser();
        if (forUser instanceof Customer customer) {
            context.sendMessage(conversation.addMessage(
                    new AssistantMessage("Hi %s! How can I assist you today?".formatted(customer.getDisplayName()))));
            context.bindProtected("customer", customer);
        } else {
            logger.warn("greetCustomer: forUser is not a Customer: {}", forUser);
        }
        return new ChitchatState();
    }

    /**
     * State for General chat, nothing specific
     */
    @State
    static class ChitchatState implements AirState {

        @Action(
                pre = "shouldRespond",
                canRerun = true
        )
        AirState respond(
                Conversation conversation,
                Customer customer,
                ActionContext context,
                @Provided AirProperties properties,
                @Provided AirlinePolicies airlinePolicies,
                @Provided EntityViewService entityViewService) {
            var assistantMessage = context.
                    ai()
                    .withLlm(properties.chatLlm())
                    .withId("chitchat.respond")
                    .withReference(airlinePolicies.rag())
                    .withReference(entityViewService.viewOf(customer))
                    .withTools(commonTools())
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
    ) implements SubState {

        @Override
        public String purpose() {
            return "Help the customer manage their reservation %s".formatted(
                    reservation.fullText());
        }

        @Action
        AirState note(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            context.sendAndSave(new AssistantMessage("""
                    I found reservation %s for you. How can I help?
                    """.formatted(reservation.getBookingReference())));
            return this;
        }

        @Override
        public AirState respond(
                Conversation conversation,
                Customer customer,
                ActionContext context,
                AirProperties properties,
                AirlinePolicies airlinePolicies,
                EntityViewService entityViewService) {
            var assistantMessage = context.
                    ai()
                    .withLlm(properties.chatLlm())
                    .withId("manage_reservation.respond")
                    .withReference(airlinePolicies.rag())
                    .withReference(entityViewService.viewOf(customer))
                    .withTools(commonTools())
                    .withTemplate("reservation")
                    .respondWithSystemPrompt(
                            conversation,
                            Map.of(
                                    "properties", properties,
                                    "reservation", reservation
                            ));
            context.sendAndSave(assistantMessage);
            return this;
        }
    }

    static class EscalationState implements AirState {

        @Action
        void escalate(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            context.sendAndSave(new AssistantMessage("""
                    You're important to us and I'll get one
                    of my human colleagues to assist you right away.
                    """));
        }
    }

    /**
     * Tool to exit the current state
     */
    private static Tool escalationTool() {
        var description = """
                Escalate the current conversation to a human agent.
                YOU MUST INVOKE THIS TOOL
                when you are unable to assist the customer
                or when the customer determinedly requests human assistance
                rather than responding directly
                """;
        var rawTool = Tool.create("escalate", description, input -> Tool.Result.withArtifact("Escalating to human agent", Boolean.TRUE));
        return Tool.replanAndAdd(rawTool, r -> {
            logger.info("Escalation tool invoked, transitioning to EscalationState");
            return new EscalationState();
        });
    }

    private static List<Tool> commonTools() {
        return List.of(
                escalationTool()
        );
    }
}