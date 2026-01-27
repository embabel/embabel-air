package com.embabel.air;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.shared.communication.PushMode;
import com.vaadin.flow.shared.ui.Transport;
import com.vaadin.flow.theme.Theme;

/**
 * Vaadin app shell configuration with Push enabled for async UI updates.
 * Using Long Polling transport for better compatibility with Spring Security.
 */
@Push(value = PushMode.AUTOMATIC, transport = Transport.LONG_POLLING)
@Theme("embabel-air")
public class AppShellConfig implements AppShellConfigurator {
}
