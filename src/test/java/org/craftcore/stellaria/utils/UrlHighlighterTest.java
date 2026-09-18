package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UrlHighlighterTest {

    @Test
    void preservesMatchedParenthesesInUrlClickTargetWhileExcludingTrailingPeriod() {
        Component component = UrlHighlighter.highlight(
                "Read https://en.wikipedia.org/wiki/Function_(mathematics).", false, null);

        ClickEvent clickEvent = findClickEvent(component);

        assertNotNull(clickEvent);
        assertEquals("https://en.wikipedia.org/wiki/Function_(mathematics)", clickEvent.value());
        assertEquals("Read https://en.wikipedia.org/wiki/Function_(mathematics).",
                PlainTextComponentSerializer.plainText().serialize(component));
    }

    private static ClickEvent findClickEvent(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent event = findClickEvent(child);
            if (event != null) {
                return event;
            }
        }
        return null;
    }
}
