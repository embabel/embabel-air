package com.embabel.air.ai.vaadin;

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.Blackboard;
import com.embabel.air.ai.agent.ChatActions.AirState;
import com.embabel.air.backend.Customer;
import com.embabel.chat.Asset;
import com.embabel.chat.AssetView;
import com.embabel.chat.ChatSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.ShortcutRegistration;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * Session panel showing user/conversation-specific content.
 * Opened by clicking on the user profile.
 */
class SessionPanel extends Div {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final VerticalLayout sidePanel;
    private final Div backdrop;
    private ShortcutRegistration escapeShortcut;

    private final Supplier<ChatSession> sessionSupplier;
    private final AgentPlatform agentPlatform;

    // Content panels
    private final VerticalLayout assetsContent;
    private final VerticalLayout stateContent;

    SessionPanel(Customer user, Supplier<ChatSession> sessionSupplier, AgentPlatform agentPlatform) {
        this.sessionSupplier = sessionSupplier;
        this.agentPlatform = agentPlatform;

        addClassName("session-panel-container");

        // Backdrop for closing panel when clicking outside
        backdrop = new Div();
        backdrop.addClassName("side-panel-backdrop");
        backdrop.addClickListener(e -> close());

        // Side panel
        sidePanel = new VerticalLayout();
        sidePanel.addClassName("side-panel");
        sidePanel.addClassName("session-panel");
        sidePanel.setPadding(false);
        sidePanel.setSpacing(false);

        // Header with user info and close button
        var header = new HorizontalLayout();
        header.addClassName("side-panel-header");
        header.setWidthFull();

        var userInfo = new HorizontalLayout();
        userInfo.setAlignItems(FlexComponent.Alignment.CENTER);
        userInfo.setSpacing(true);

        // Avatar
        var initials = getInitials(user.getDisplayName());
        var avatar = new Div();
        avatar.setText(initials);
        avatar.getStyle()
                .set("width", "32px")
                .set("height", "32px")
                .set("border-radius", "50%")
                .set("background", "var(--sb-accent)")
                .set("color", "white")
                .set("display", "flex")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("font-weight", "600");

        var title = new Span(user.getDisplayName());
        title.addClassName("side-panel-title");

        userInfo.add(avatar, title);

        var closeButton = new Button(new Icon(VaadinIcon.CLOSE));
        closeButton.addClassName("side-panel-close");
        closeButton.addClickListener(e -> close());

        header.add(userInfo, closeButton);
        header.setFlexGrow(1, userInfo);
        sidePanel.add(header);

        // Tabs
        var assetsTab = new Tab(VaadinIcon.CUBE.create(), new Span("Assets"));
        var stateTab = new Tab(VaadinIcon.COG.create(), new Span("State"));

        var tabs = new Tabs(assetsTab, stateTab);
        tabs.setWidthFull();
        sidePanel.add(tabs);

        // Content area
        var contentArea = new VerticalLayout();
        contentArea.addClassName("side-panel-content");
        contentArea.setPadding(false);
        contentArea.setSizeFull();

        // Assets content
        assetsContent = new VerticalLayout();
        assetsContent.setPadding(true);
        assetsContent.setSpacing(true);

        // State content
        stateContent = new VerticalLayout();
        stateContent.setPadding(true);
        stateContent.setSpacing(true);
        stateContent.setVisible(false);

        contentArea.add(assetsContent, stateContent);
        sidePanel.add(contentArea);
        sidePanel.setFlexGrow(1, contentArea);

        // Tab switching
        tabs.addSelectedChangeListener(event -> {
            var selected = event.getSelectedTab();
            assetsContent.setVisible(selected == assetsTab);
            stateContent.setVisible(selected == stateTab);

            if (selected == assetsTab) {
                refreshAssets();
            } else if (selected == stateTab) {
                refreshState();
            }
        });

        // Add elements
        getElement().appendChild(backdrop.getElement());
        getElement().appendChild(sidePanel.getElement());
    }

    private void refreshAssets() {
        assetsContent.removeAll();

        var session = sessionSupplier.get();
        if (session == null) {
            assetsContent.add(new Span("No active session"));
            return;
        }

        AssetView assetView = session.getConversation().getAssetTracker();
        if (assetView == null || assetView.getAssets().isEmpty()) {
            var emptyMessage = new Span("No assets yet. Assets are added when tools produce outputs.");
            emptyMessage.addClassName("empty-list-label");
            assetsContent.add(emptyMessage);
            return;
        }

        var assetsTitle = new H4("Assets");
        assetsTitle.addClassName("section-title");
        assetsContent.add(assetsTitle);

        for (var asset : assetView.getAssets()) {
            assetsContent.add(createAssetCard(asset));
        }
    }

    private Div createAssetCard(Asset asset) {
        var card = new Div();
        card.addClassName("asset-card");
        card.getStyle()
                .set("background", "var(--sb-bg-light)")
                .set("border", "1px solid var(--sb-border)")
                .set("border-left", "3px solid var(--sb-accent)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-s)")
                .set("margin-bottom", "var(--lumo-space-s)");

        var reference = asset.reference();

        // Header with name and time
        var headerRow = new HorizontalLayout();
        headerRow.setWidthFull();
        headerRow.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        headerRow.setAlignItems(FlexComponent.Alignment.CENTER);
        headerRow.setPadding(false);
        headerRow.setSpacing(false);

        var name = new Span(reference.getName());
        name.getStyle()
                .set("color", "var(--sb-accent)")
                .set("font-weight", "600");

        var timestamp = asset.getTimestamp()
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT);
        var time = new Span(timestamp);
        time.getStyle()
                .set("color", "var(--sb-text-muted)")
                .set("font-size", "var(--lumo-font-size-xs)");

        headerRow.add(name, time);

        // Description
        var description = new Span(reference.getDescription());
        description.getStyle()
                .set("color", "var(--sb-text-secondary)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("display", "block")
                .set("margin-top", "var(--lumo-space-xs)");

        card.add(headerRow, description);

        // Notes if available
        var notes = reference.notes();
        if (notes != null && !notes.isBlank()) {
            var notesSpan = new Span(notes);
            notesSpan.getStyle()
                    .set("color", "var(--sb-text-muted)")
                    .set("font-size", "var(--lumo-font-size-xs)")
                    .set("font-style", "italic")
                    .set("display", "block")
                    .set("margin-top", "var(--lumo-space-xs)");
            card.add(notesSpan);
        }

        return card;
    }

    private void refreshState() {
        stateContent.removeAll();

        var session = sessionSupplier.get();
        if (session == null) {
            stateContent.add(new Span("No active session"));
            return;
        }

        var processId = session.getProcessId();
        if (processId == null) {
            stateContent.add(new Span("No process ID available"));
            return;
        }

        var agentProcess = agentPlatform.getAgentProcess(processId);
        if (agentProcess == null) {
            stateContent.add(new Span("Process not found"));
            return;
        }

        Blackboard blackboard = agentProcess.getBlackboard();
        var airState = blackboard.last(AirState.class);

        var stateTitle = new H4("Current State");
        stateTitle.addClassName("section-title");
        stateContent.add(stateTitle);

        if (airState == null) {
            stateContent.add(new Span("No AirState on blackboard"));
            return;
        }

        // State type
        var stateType = new Div();
        stateType.getStyle()
                .set("background", "var(--sb-accent)")
                .set("color", "white")
                .set("padding", "var(--lumo-space-xs) var(--lumo-space-s)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("display", "inline-block")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("margin-bottom", "var(--lumo-space-m)");
        stateType.setText(airState.getClass().getSimpleName());
        stateContent.add(stateType);

        // JSON representation
        var jsonTitle = new Span("JSON:");
        jsonTitle.getStyle()
                .set("color", "var(--sb-text-secondary)")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("display", "block")
                .set("margin-bottom", "var(--lumo-space-xs)");
        stateContent.add(jsonTitle);

        var jsonContent = new Div();
        jsonContent.getStyle()
                .set("background", "var(--sb-bg-light)")
                .set("border", "1px solid var(--sb-border)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "var(--lumo-space-s)")
                .set("font-family", "monospace")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("white-space", "pre-wrap")
                .set("overflow-x", "auto")
                .set("color", "var(--sb-text-primary)");

        try {
            var json = objectMapper.writeValueAsString(airState);
            jsonContent.setText(json);
        } catch (JsonProcessingException e) {
            jsonContent.setText("Error serializing state: " + e.getMessage());
        }

        stateContent.add(jsonContent);

        // Also show blackboard summary
        var blackboardTitle = new H4("Blackboard Objects");
        blackboardTitle.addClassName("section-title");
        blackboardTitle.getStyle().set("margin-top", "var(--lumo-space-l)");
        stateContent.add(blackboardTitle);

        var objectsList = new VerticalLayout();
        objectsList.setPadding(false);
        objectsList.setSpacing(false);

        var objects = blackboard.getObjects();
        if (objects.isEmpty()) {
            objectsList.add(new Span("No objects on blackboard"));
        } else {
            for (var obj : objects) {
                var objRow = new Div();
                objRow.getStyle()
                        .set("padding", "var(--lumo-space-xs) 0")
                        .set("border-bottom", "1px solid var(--sb-border)")
                        .set("font-size", "var(--lumo-font-size-s)");

                var typeBadge = new Span(obj.getClass().getSimpleName());
                typeBadge.getStyle()
                        .set("background", "var(--sb-bg-medium)")
                        .set("color", "var(--sb-text-secondary)")
                        .set("padding", "2px 6px")
                        .set("border-radius", "4px")
                        .set("font-size", "var(--lumo-font-size-xs)")
                        .set("margin-right", "var(--lumo-space-s)");

                objRow.add(typeBadge);
                objectsList.add(objRow);
            }
        }
        stateContent.add(objectsList);
    }

    private String getInitials(String name) {
        if (name == null || name.isBlank()) return "?";
        var parts = name.trim().split("\\s+");
        if (parts.length >= 2) {
            return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }

    public void open() {
        refreshAssets();
        sidePanel.addClassName("open");
        backdrop.addClassName("visible");
        escapeShortcut = getUI().map(ui ->
                ui.addShortcutListener(this::close, Key.ESCAPE)
        ).orElse(null);
    }

    public void close() {
        sidePanel.removeClassName("open");
        backdrop.removeClassName("visible");
        if (escapeShortcut != null) {
            escapeShortcut.remove();
            escapeShortcut = null;
        }
    }

    public boolean isOpen() {
        return sidePanel.hasClassName("open");
    }
}
