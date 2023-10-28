package com.noxcrew.noxesium.feature.render.cache.actionbar;

import com.noxcrew.noxesium.feature.render.cache.ElementInformation;
import com.noxcrew.noxesium.feature.render.font.BakedComponent;
import net.minecraft.network.chat.Component;

/**
 * Stores information about the state of the action bar.
 *
 * @param component The current text
 */
public record ActionBarInformation(
        BakedComponent component
) implements ElementInformation {

    /**
     * The fallback contents if the action bar is absent.
     */
    public static final ActionBarInformation EMPTY = new ActionBarInformation(BakedComponent.EMPTY);
}
