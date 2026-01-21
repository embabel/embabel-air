package com.embabel.air.ai.vaadin;

import com.embabel.air.ai.rag.DocumentService;
import com.embabel.air.backend.Customer;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.ShortcutRegistration;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/**
 * Right-side drawer for document management: shows document listing.
 */
public class DocumentsDrawer extends Div {

    private final VerticalLayout sidePanel;
    private final Div backdrop;
    private final Button toggleButton;
    private ShortcutRegistration escapeShortcut;

    private final DocumentListSection documentsSection;

    public DocumentsDrawer(DocumentService documentService, Customer user, Runnable onDocumentsChanged) {
        // Backdrop for closing panel when clicking outside
        backdrop = new Div();
        backdrop.addClassName("side-panel-backdrop");
        backdrop.addClickListener(e -> close());

        // Toggle button on right edge
        toggleButton = new Button(VaadinIcon.ELLIPSIS_DOTS_V.create());
        toggleButton.addClassName("side-panel-toggle");
        toggleButton.getElement().setAttribute("title", "Documents");
        toggleButton.addClickListener(e -> open());

        // Side panel
        sidePanel = new VerticalLayout();
        sidePanel.addClassName("side-panel");
        sidePanel.setPadding(false);
        sidePanel.setSpacing(false);

        // Header with close button
        var header = new HorizontalLayout();
        header.addClassName("side-panel-header");
        header.setWidthFull();

        var title = new Span("Documents");
        title.addClassName("side-panel-title");

        var closeButton = new Button(new Icon(VaadinIcon.CLOSE));
        closeButton.addClassName("side-panel-close");
        closeButton.addClickListener(e -> close());

        header.add(title, closeButton);
        header.setFlexGrow(1, title);
        sidePanel.add(header);

        // Content area
        var contentArea = new VerticalLayout();
        contentArea.addClassName("side-panel-content");
        contentArea.setPadding(false);
        contentArea.setSizeFull();

        // Create document list section
        documentsSection = new DocumentListSection(documentService, onDocumentsChanged);

        contentArea.add(documentsSection);
        sidePanel.add(contentArea);
        sidePanel.setFlexGrow(1, contentArea);

        // Add elements
        getElement().appendChild(backdrop.getElement());
        getElement().appendChild(toggleButton.getElement());
        getElement().appendChild(sidePanel.getElement());
    }

    public void open() {
        sidePanel.addClassName("open");
        backdrop.addClassName("visible");
        toggleButton.addClassName("hidden");
        escapeShortcut = getUI().map(ui ->
                ui.addShortcutListener(this::close, Key.ESCAPE)
        ).orElse(null);
        documentsSection.refresh();
    }

    public void close() {
        sidePanel.removeClassName("open");
        backdrop.removeClassName("visible");
        toggleButton.removeClassName("hidden");
        if (escapeShortcut != null) {
            escapeShortcut.remove();
            escapeShortcut = null;
        }
    }

    public void refresh() {
        documentsSection.refresh();
    }
}
