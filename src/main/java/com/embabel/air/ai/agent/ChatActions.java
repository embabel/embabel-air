package com.embabel.air.ai.agent;

import com.embabel.agent.api.annotation.*;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.tool.Tool;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.ai.rag.RagConfiguration.AirlinePolicies;
import com.embabel.air.backend.BookingService;
import com.embabel.air.backend.Customer;
import com.embabel.air.backend.Reservation;
import com.embabel.air.backend.ReservationRepository;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.Conversation;
import com.embabel.chat.Message;
import com.embabel.dice.agent.Memory;
import com.embabel.dice.common.ConversationAnalysisRequestEvent;
import com.embabel.dice.projection.memory.MemoryProjector;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.springdata.EntityViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The platform can use any action to respond to user messages.
 */
@EmbabelComponent
public class ChatActions {

    private static final Logger logger = LoggerFactory.getLogger(ChatActions.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ApplicationEventPublisher eventPublisher;
    private final AirProperties airProperties;
    private final BookingService bookingService;

    public ChatActions(ApplicationEventPublisher eventPublisher, AirProperties airProperties,
                       BookingService bookingService) {
        this.eventPublisher = eventPublisher;
        this.airProperties = airProperties;
        this.bookingService = bookingService;
    }

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
        return new ChitchatState(eventPublisher, airProperties, bookingService);
    }

    /**
     * State for General chat, nothing specific
     */
    @State
    static class ChitchatState implements AirState {

        private final ApplicationEventPublisher eventPublisher;
        private final AirProperties airProperties;
        private final BookingService bookingService;

        ChitchatState(ApplicationEventPublisher eventPublisher, AirProperties airProperties,
                      BookingService bookingService) {
            this.eventPublisher = eventPublisher;
            this.airProperties = airProperties;
            this.bookingService = bookingService;
        }

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
                @Provided EntityViewService entityViewService,
                @Provided PropositionRepository propositionRepository,
                @Provided MemoryProjector memoryProjector) {

            var references = new LinkedList<>(
                    conversation.getAssetTracker().mostRecentlyAdded(1).references());
            references.add(airlinePolicies.reference());
            references.add(entityViewService.entityReferenceFor(customer));
            if (properties.memory().getEnabled()) {
                String recentContext = conversation.getMessages().stream()
                        // TODO fix hardcoding
                        .skip(Math.max(0, conversation.getMessages().size() - 15))
                        .map(Message::getContent)
                        .collect(Collectors.joining("\n"));
                references.add(
                        Memory.forContext(customer.getId())
                                .withRepository(propositionRepository)
                                .withProjector(memoryProjector)
                                .withEagerSearchAbout(recentContext, properties.memory().getExistingPropositionsToShow())
                );
            }

            var tools = new LinkedList<>(commonTools());
            tools.addAll(bookingTools(bookingService, customer));
            tools.addAll(conversation.getAssetTracker().addAnyReturnedAssets(
                    entityViewService.repositoryToolsFor(ReservationRepository.class)));
            tools.addAll(conversation.getAssetTracker().addAnyReturnedAssets(
                    List.of(entityViewService.finderFor(Reservation.class))));

            var assistantMessage = context.
                    ai()
                    .withLlm(properties.chatLlm())
                    .withId("chitchat.respond")
                    .withReferences(references)
                    .withTools(tools)
                    .rendering("air")
                    .respondWithSystemPrompt(
                            conversation,
                            Map.of(
                                    "properties", properties
                            ));
            context.sendAndSave(assistantMessage);

            if (airProperties.memory() != null && airProperties.memory().getEnabled()) {
                eventPublisher.publishEvent(
                        new ConversationAnalysisRequestEvent(this, customer, conversation));
            }

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

    private static Tool searchFlightsTool(BookingService bookingService) {
        var description = """
                Search for available flights between two airports on a given date.
                Returns direct and one-stop connecting itineraries with prices.
                Input JSON: {"fromAirport": "JFK", "toAirport": "LAX", "date": "2026-03-15"}
                - fromAirport: 3-letter IATA airport code for departure
                - toAirport: 3-letter IATA airport code for arrival
                - date: departure date in YYYY-MM-DD format
                """;
        return Tool.create("search_flights", description, input -> {
            try {
                var node = objectMapper.readTree(input);
                var from = node.get("fromAirport").asText();
                var to = node.get("toAirport").asText();
                var date = LocalDate.parse(node.get("date").asText());

                var itineraries = bookingService.searchRoutes(from, to, date, 1);
                if (itineraries.isEmpty()) {
                    return Tool.Result.text("No flights found from %s to %s on %s.".formatted(from, to, date));
                }

                var sb = new StringBuilder();
                sb.append("Found %d itinerary(ies) from %s to %s on %s:\n\n".formatted(
                        itineraries.size(), from, to, date));
                for (int i = 0; i < itineraries.size(); i++) {
                    sb.append("Option %d: %s\n\n".formatted(i + 1, itineraries.get(i).summary()));
                }
                return Tool.Result.text(sb.toString());
            } catch (Exception e) {
                logger.error("search_flights error", e);
                return Tool.Result.text("Error searching flights: " + e.getMessage());
            }
        });
    }

    private static Tool bookFlightTool(BookingService bookingService, Customer customer) {
        var description = """
                Book flights for the current customer using flight segment IDs from search results.
                Input JSON: {"flightSegmentIds": "id1,id2"}
                - flightSegmentIds: comma-separated flight segment IDs to book
                """;
        return Tool.create("book_flight", description, input -> {
            try {
                var node = objectMapper.readTree(input);
                var idsRaw = node.get("flightSegmentIds").asText();
                var ids = Arrays.stream(idsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

                var reservation = bookingService.book(customer, ids);
                var segments = reservation.getFlightSegments();
                var sb = new StringBuilder();
                sb.append("Booking confirmed! Reference: %s\n".formatted(reservation.getBookingReference()));
                sb.append("Total: $%s\n".formatted(reservation.getPaidAmount()));
                for (var seg : segments) {
                    sb.append("  %s → %s departing %s arriving %s\n".formatted(
                            seg.getDepartureAirportCode(), seg.getArrivalAirportCode(),
                            seg.getDepartureDateTime(), seg.getArrivalDateTime()));
                }
                return Tool.Result.text(sb.toString());
            } catch (Exception e) {
                logger.error("book_flight error", e);
                return Tool.Result.text("Error booking flight: " + e.getMessage());
            }
        });
    }

    private static Tool cancelBookingTool(BookingService bookingService) {
        var description = """
                Cancel an existing reservation by its booking reference.
                Input JSON: {"bookingReference": "ABC123"}
                - bookingReference: the 6-character booking reference code
                """;
        return Tool.create("cancel_booking", description, input -> {
            try {
                var node = objectMapper.readTree(input);
                var ref = node.get("bookingReference").asText();
                bookingService.cancel(ref);
                return Tool.Result.text("Reservation %s has been cancelled successfully.".formatted(ref));
            } catch (Exception e) {
                logger.error("cancel_booking error", e);
                return Tool.Result.text("Error cancelling booking: " + e.getMessage());
            }
        });
    }

    private static Tool rebookFlightTool(BookingService bookingService) {
        var description = """
                Rebook an existing reservation onto different flights.
                Cancels the old flight segments and books new ones, preserving the booking reference.
                Input JSON: {"bookingReference": "ABC123", "newFlightSegmentIds": "id1,id2"}
                - bookingReference: the 6-character booking reference to rebook
                - newFlightSegmentIds: comma-separated new flight segment IDs
                """;
        return Tool.create("rebook_flight", description, input -> {
            try {
                var node = objectMapper.readTree(input);
                var ref = node.get("bookingReference").asText();
                var idsRaw = node.get("newFlightSegmentIds").asText();
                var ids = Arrays.stream(idsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

                var reservation = bookingService.rebook(ref, ids);
                var sb = new StringBuilder();
                sb.append("Rebooked! Reference: %s\n".formatted(reservation.getBookingReference()));
                sb.append("New total: $%s\n".formatted(reservation.getPaidAmount()));
                for (var seg : reservation.getFlightSegments()) {
                    sb.append("  %s → %s departing %s arriving %s\n".formatted(
                            seg.getDepartureAirportCode(), seg.getArrivalAirportCode(),
                            seg.getDepartureDateTime(), seg.getArrivalDateTime()));
                }
                return Tool.Result.text(sb.toString());
            } catch (Exception e) {
                logger.error("rebook_flight error", e);
                return Tool.Result.text("Error rebooking: " + e.getMessage());
            }
        });
    }

    private static List<Tool> bookingTools(BookingService bookingService, Customer customer) {
        return List.of(
                searchFlightsTool(bookingService),
                bookFlightTool(bookingService, customer),
                cancelBookingTool(bookingService),
                rebookFlightTool(bookingService)
        );
    }
}