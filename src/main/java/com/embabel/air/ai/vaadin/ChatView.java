package com.embabel.air.ai.vaadin;

import com.embabel.agent.api.channel.MessageOutputChannelEvent;
import com.embabel.agent.api.channel.OutputChannel;
import com.embabel.agent.api.channel.OutputChannelEvent;
import com.embabel.agent.api.channel.ProgressOutputChannelEvent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.air.ai.AirProperties;
import com.embabel.air.ai.rag.DocumentService;
import com.embabel.air.backend.Customer;
import com.embabel.air.backend.CustomerService;
import com.embabel.chat.AssistantMessage;
import com.embabel.chat.ChatSession;
import com.embabel.chat.Chatbot;
import com.embabel.chat.UserMessage;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction;
import com.embabel.vaadin.component.ChatMessageBubble;
import com.embabel.vaadin.component.Footer;
import com.embabel.vaadin.component.UserSection;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import jakarta.annotation.security.PermitAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vaadin-based chat view for the chatbot.
 */
@Route("")
@PageTitle("Embabel Air")
@PermitAll
public class ChatView extends VerticalLayout {

    private static final Logger logger = LoggerFactory.getLogger(ChatView.class);

    private static final String SESSION_DATA_KEY = "sessionData";

    private final Chatbot chatbot;
    private final String persona;
    private final AirProperties properties;
    private final DocumentService documentService;
    private final Customer currentUser;
    private final AgentPlatform agentPlatform;
    private final PropositionRepository propositionRepository;

    private VerticalLayout messagesLayout;
    private Scroller messagesScroller;
    private TextField inputField;
    private Button sendButton;
    private Footer footer;
    private SessionPanel sessionPanel;

    private final IncrementalPropositionExtraction propositionExtraction;

    public ChatView(Chatbot chatbot, AirProperties properties, DocumentService documentService,
                    CustomerService userService, AgentPlatform agentPlatform,
                    PropositionRepository propositionRepository,
                    IncrementalPropositionExtraction propositionExtraction) {
        this.chatbot = chatbot;
        this.properties = properties;
        this.documentService = documentService;
        this.currentUser = userService.getAuthenticatedUser();
        this.agentPlatform = agentPlatform;
        this.propositionRepository = propositionRepository;
        this.propositionExtraction = propositionExtraction;
        this.persona = "Emmie";

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        // Header row with title and user section
        var headerRow = new HorizontalLayout();
        headerRow.setWidthFull();
        headerRow.setAlignItems(Alignment.CENTER);
        headerRow.setJustifyContentMode(JustifyContentMode.BETWEEN);
        headerRow.setPadding(false);

        // Logo/header image (left)
        var headerImage = new Image("images/embabel-air.jpg", "Embabel Air");
        headerImage.addClassName("header-logo");
        headerImage.setHeight("60px");

        // User section (right) - clicking opens session panel
        var userSection = new UserSection(currentUser, this::toggleSessionPanel);
        var logoutButton = new Button("Logout", e -> getUI().ifPresent(ui -> ui.getPage().setLocation("/logout")));
        logoutButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        logoutButton.addClassName("logout-button");
        var userArea = new HorizontalLayout(userSection, logoutButton);
        userArea.setAlignItems(Alignment.CENTER);
        headerRow.add(headerImage, userArea);
        add(headerRow);

        // Session panel (drawer from right)
        sessionPanel = new SessionPanel(currentUser, this::getCurrentSession, agentPlatform, propositionRepository, propositionExtraction);
        getElement().appendChild(sessionPanel.getElement());

        // Messages container
        messagesLayout = new VerticalLayout();
        messagesLayout.setWidthFull();
        messagesLayout.setPadding(false);
        messagesLayout.setSpacing(true);

        messagesScroller = new Scroller(messagesLayout);
        messagesScroller.setSizeFull();
        messagesScroller.setScrollDirection(Scroller.ScrollDirection.VERTICAL);
        messagesScroller.addClassName("chat-scroller");
        add(messagesScroller);
        setFlexGrow(1, messagesScroller);

        // Restore previous messages if session exists
        restorePreviousMessages();

        // Input section
        add(createInputSection());

        // Footer
        footer = new Footer(documentService.getDocumentCount() + " documents \u00b7 " + documentService.getChunkCount() + " chunks");
        add(footer);

        // Documents drawer
        var drawer = new DocumentsDrawer(documentService, currentUser, this::refreshFooter);
        getElement().appendChild(drawer.getElement());

        // Initialize session on attach (kicks off agent process and greeting)
        addAttachListener(event -> {
            var ui = event.getUI();
            initializeSession(ui);
        });
    }

    private void initializeSession(UI ui) {
        var vaadinSession = VaadinSession.getCurrent();
        var sessionData = (SessionData) vaadinSession.getAttribute(SESSION_DATA_KEY);

        if (sessionData != null) {
            // Session already exists — update the output channel's UI reference
            // in case the UI was recreated (e.g., page refresh, reconnect)
            sessionData.outputChannel().updateUI(ui);
            return;
        }

        // Create session with output channel that directly updates UI
        var outputChannel = new VaadinOutputChannel(ui);
        var chatSession = chatbot.createSession(currentUser, outputChannel, null, UUID.randomUUID().toString());
        sessionData = new SessionData(chatSession, outputChannel);
        vaadinSession.setAttribute(SESSION_DATA_KEY, sessionData);
        logger.info("Created new chat session");
        // Greeting will be displayed automatically when it arrives via the output channel
    }

    private void refreshFooter() {
        remove(footer);
        footer = new Footer(documentService.getDocumentCount() + " documents \u00b7 " + documentService.getChunkCount() + " chunks");
        add(footer);
    }

    private void toggleSessionPanel() {
        if (sessionPanel.isOpen()) {
            sessionPanel.close();
        } else {
            sessionPanel.open();
        }
    }

    private ChatSession getCurrentSession() {
        var vaadinSession = VaadinSession.getCurrent();
        var sessionData = (SessionData) vaadinSession.getAttribute(SESSION_DATA_KEY);
        return sessionData != null ? sessionData.chatSession() : null;
    }

    private record SessionData(ChatSession chatSession, VaadinOutputChannel outputChannel) {
    }

    private SessionData getOrCreateSession(UI ui) {
        var vaadinSession = VaadinSession.getCurrent();
        var sessionData = (SessionData) vaadinSession.getAttribute(SESSION_DATA_KEY);

        if (sessionData == null) {
            var outputChannel = new VaadinOutputChannel(ui);
            var chatSession = chatbot.createSession(currentUser, outputChannel, null, UUID.randomUUID().toString());
            sessionData = new SessionData(chatSession, outputChannel);
            vaadinSession.setAttribute(SESSION_DATA_KEY, sessionData);
            logger.info("Created new chat session");
        }

        return sessionData;
    }

    private HorizontalLayout createInputSection() {
        var inputSection = new HorizontalLayout();
        inputSection.setWidthFull();
        inputSection.setPadding(false);
        inputSection.setAlignItems(Alignment.CENTER);

        inputField = new TextField();
        inputField.setPlaceholder("Type your message...");
        inputField.setWidthFull();
        inputField.setClearButtonVisible(true);
        inputField.getElement().setAttribute("autocomplete", "off");
        inputField.addKeyPressListener(Key.ENTER, e -> sendMessage());

        sendButton = new Button("Send", VaadinIcon.PAPERPLANE.create());
        sendButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        sendButton.addClickListener(e -> sendMessage());

        inputSection.add(inputField, sendButton);
        inputSection.setFlexGrow(1, inputField);

        return inputSection;
    }

    private void sendMessage() {
        var text = inputField.getValue();
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        inputField.clear();
        inputField.setEnabled(false);
        sendButton.setEnabled(false);

        // Add user message to UI
        messagesLayout.add(ChatMessageBubble.user(text));
        scrollToBottom();

        var ui = getUI().orElse(null);
        if (ui == null) return;

        var sessionData = getOrCreateSession(ui);

        // Set up a future to wait for the response
        var responseFuture = new CompletableFuture<Void>();
        sessionData.outputChannel().expectResponse(responseFuture);

        new Thread(() -> {
            try {
                var userMessage = new UserMessage(text);
                logger.info("Sending user message: {}", text);
                sessionData.chatSession().onUserMessage(userMessage);

                // onUserMessage is synchronous - by the time it returns, any response
                // should have been sent through the output channel. If not, complete
                // the future anyway to avoid hanging.
                if (!responseFuture.isDone()) {
                    logger.warn("No response received from chatbot for message: {}", text);
                    responseFuture.complete(null);
                    ui.access(() -> {
                        // Remove any pending tool call indicator
                        if (sessionData.outputChannel().currentToolCallIndicator != null) {
                            messagesLayout.remove(sessionData.outputChannel().currentToolCallIndicator);
                            sessionData.outputChannel().currentToolCallIndicator = null;
                        }
                        messagesLayout.add(ChatMessageBubble.error("No response received. Please try again."));
                        scrollToBottom();
                    });
                }

                ui.access(() -> {
                    inputField.setEnabled(true);
                    sendButton.setEnabled(true);
                    inputField.focus();
                });
            } catch (Exception e) {
                logger.error("Error getting chatbot response", e);
                sessionData.outputChannel().clearExpectedResponse();
                ui.access(() -> {
                    messagesLayout.add(ChatMessageBubble.error("Error: " + e.getMessage()));
                    scrollToBottom();
                    inputField.setEnabled(true);
                    sendButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void scrollToBottom() {
        messagesScroller.getElement().executeJs("this.scrollTop = this.scrollHeight");
    }

    private void restorePreviousMessages() {
        var vaadinSession = VaadinSession.getCurrent();
        var sessionData = (SessionData) vaadinSession.getAttribute(SESSION_DATA_KEY);
        if (sessionData == null) {
            return;
        }

        var conversation = sessionData.chatSession().getConversation();
        for (var message : conversation.getMessages()) {
            if (message instanceof UserMessage) {
                messagesLayout.add(ChatMessageBubble.user(message.getContent()));
            } else if (message instanceof AssistantMessage) {
                messagesLayout.add(ChatMessageBubble.assistant(persona, message.getContent()));
            }
        }

        if (!conversation.getMessages().isEmpty()) {
            scrollToBottom();
        }
    }

    /**
     * Output channel that directly displays messages in the UI.
     * Uses CompletableFuture to signal when a response to a user message has been received.
     */
    private class VaadinOutputChannel implements OutputChannel {
        private volatile UI ui;
        private final AtomicReference<CompletableFuture<Void>> pendingResponse = new AtomicReference<>();
        volatile Div currentToolCallIndicator; // package-private for access from sendMessage

        VaadinOutputChannel(UI ui) {
            this.ui = ui;
        }

        /**
         * Update the UI reference when the UI is recreated (e.g., page refresh, reconnect).
         */
        void updateUI(UI ui) {
            this.ui = ui;
        }

        /**
         * Set a future that will be completed when the next assistant message arrives.
         */
        void expectResponse(CompletableFuture<Void> future) {
            pendingResponse.set(future);
        }

        /**
         * Clear the pending response future (e.g., on timeout or error).
         */
        void clearExpectedResponse() {
            pendingResponse.set(null);
        }

        @Override
        public void send(OutputChannelEvent event) {
            if (event instanceof MessageOutputChannelEvent msgEvent) {
                var msg = msgEvent.getMessage();
                if (msg instanceof AssistantMessage assistantMsg) {
                    ui.access(() -> {
                        // Remove tool call indicator before showing response
                        if (currentToolCallIndicator != null) {
                            messagesLayout.remove(currentToolCallIndicator);
                            currentToolCallIndicator = null;
                        }
                        // Add the message to the UI
                        messagesLayout.add(ChatMessageBubble.assistant(persona, assistantMsg.getContent()));
                        scrollToBottom();
                    });

                    // Signal that we received a response
                    var future = pendingResponse.getAndSet(null);
                    if (future != null) {
                        future.complete(null);
                    }
                }
            } else if (event instanceof ProgressOutputChannelEvent progressEvent) {
                ui.access(() -> {
                    // Remove previous indicator if exists
                    if (currentToolCallIndicator != null) {
                        messagesLayout.remove(currentToolCallIndicator);
                    }
                    // Create new tool call indicator
                    currentToolCallIndicator = new Div();
                    currentToolCallIndicator.addClassName("tool-call-indicator");
                    currentToolCallIndicator.setText(progressEvent.getMessage());
                    messagesLayout.add(currentToolCallIndicator);
                    scrollToBottom();
                });
            }
        }
    }
}
