package com.embabel.air.security;

import com.embabel.air.backend.DevDataLoader;
import com.embabel.air.backend.SkyPointsStatus;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Login view for Embabel Air.
 */
@Route("login")
@PageTitle("Login | Embabel Air")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        addClassName("login-view");
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        loginForm.setAction("login");
        var i18n = LoginI18n.createDefault();
        i18n.getForm().setTitle("Embabel Air");
        loginForm.setI18n(i18n);

        var title = new H1("Embabel Air");
        title.addClassName("login-title");

        var subtitle = new Span("AI-powered assistant");
        subtitle.addClassName("login-subtitle");

        var demoSection = createDemoSection();

        add(/*title, subtitle,*/ loginForm, demoSection);

        var topUser = DevDataLoader.DEMO_USERS.stream().filter(u -> u.level() == SkyPointsStatus.Level.PLATINUM)
                .findFirst().orElseThrow();
        loginForm.getElement().executeJs(
                "setTimeout(() => {" +
                        "  this.querySelector('vaadin-text-field').value = $0;" +
                        "  this.querySelector('vaadin-password-field').value = $1;" +
                        "}, 100);",
                topUser.username(), DevDataLoader.DEFAULT_PASSWORD
        );
    }

    private Div createDemoSection() {
        var section = new Div();
        section.addClassName("demo-section");
        section.getStyle()
                .set("margin-top", "2em")
                .set("padding", "1em")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("max-width", "400px");

        var header = new Div();
        header.setText("Demo Accounts (password: " + DevDataLoader.DEFAULT_PASSWORD + ")");
        header.getStyle()
                .set("font-weight", "bold")
                .set("margin-bottom", "0.5em")
                .set("color", "var(--lumo-primary-text-color)");
        section.add(header);

        for (var user : DevDataLoader.DEMO_USERS) {
            var userRow = new Div();
            userRow.getStyle()
                    .set("padding", "0.25em 0")
                    .set("font-size", "var(--lumo-font-size-s)")
                    .set("cursor", "pointer");

            var username = new Span(user.username());
            username.getStyle()
                    .set("font-family", "monospace")
                    .set("color", "var(--lumo-primary-color)");

            var description = new Span(" - " + user.description());
            description.getStyle().set("color", "var(--lumo-secondary-text-color)");

            userRow.add(username, description);

            // Click to fill the form
            userRow.getElement().addEventListener("click", e -> {
                loginForm.getElement().executeJs(
                        "this.querySelector('vaadin-text-field').value = $0;" +
                                "this.querySelector('vaadin-password-field').value = $1;",
                        user.username(), DevDataLoader.DEFAULT_PASSWORD
                );
            });

            userRow.getElement().addEventListener("mouseover", e ->
                    userRow.getStyle().set("background", "var(--lumo-contrast-10pct)"));
            userRow.getElement().addEventListener("mouseout", e ->
                    userRow.getStyle().remove("background"));

            section.add(userRow);
        }

        return section;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Show error if login failed
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            loginForm.setError(true);
        }
    }
}
