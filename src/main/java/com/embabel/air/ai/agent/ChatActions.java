package com.embabel.air.ai.agent;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.tool.Tool;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.ai.rag.RagConfiguration.AirlinePolicies;
import com.embabel.air.backend.Customer;
import com.embabel.air.backend.Reservation;
import com.embabel.air.backend.ReservationRepository;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Conversation;
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
    public interface AirState {
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

            var assets = conversation.getAssetTracker().mostRecentlyAdded(1).references();

            var assistantMessage = context.
                    ai()
                    .withLlm(properties.chatLlm())
                    .withId("chitchat.respond")
                    .withReferences(
                            airlinePolicies.reference(),
                            entityViewService.entityReferenceFor(customer)
                    )
                    .withReferences(assets)
                    .withTools(commonTools())
                    .withTools(
                            conversation.getAssetTracker().addAnyReturnedAssets(
                                    entityViewService.repositoryToolsFor(ReservationRepository.class)
                            )
                    )
                    .withTools(
                            conversation.getAssetTracker().addAnyReturnedAssets(
                                    List.of(
                                            entityViewService.finderFor(Reservation.class)
                                    )
                            )
                    )
                    .rendering("air")
                    .respondWithSystemPrompt(
                            conversation,
                            Map.of(
                                    "properties", properties
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
            logger.info("Conversation with customer {} escalated to human agent", customer.getId());
        }
    }

    static class DoneState implements AirState {

        @Action
        @AchievesGoal(
                description = "Complete the conversation"
        )
        void conclude(
                Conversation conversation,
                Customer customer,
                ActionContext context) {
            context.sendAndSave(new AssistantMessage("""
                    It was great to talk to you! If you need any further assistance, feel free to reach out.
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

    private static Tool completionTool() {
        var description = """
                If the conversation has been successfully concluded
                because you've addressed the customer's needs,
                and there is nothing further to assist with,
                you MUST INVOKE THIS TOOL to end the conversation.
                """;
        var rawTool = Tool.create("done", description, input -> Tool.Result.withArtifact("Done", Boolean.TRUE));
        return Tool.replanAndAdd(rawTool, r -> {
            logger.info("Done tool invoked, transitioning to DoneState");
            return new DoneState();
        });
    }

    private static List<Tool> commonTools() {
        return List.of(
                escalationTool(),
                completionTool()
        );
    }
}