package org.mage.test.kanna;

import mage.abilities.common.PassAbility;
import mage.player.ai.kanna.ActionCatalog;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;

public class ActionCatalogTest {

    @Test
    public void assignsSequentialIdsInInsertionOrder() {
        ActionCatalog catalog = new ActionCatalog();
        PassAbility first = new PassAbility();
        PassAbility second = new PassAbility();
        catalog.add(first, "Pass");
        catalog.add(second, "Pass again");
        assertEquals("act-0", catalog.idFor(first));
        assertEquals("act-1", catalog.idFor(second));
        assertEquals(2, catalog.size());
    }

    @Test
    public void resolvesIdBackToTheSameAbilityInstance() {
        ActionCatalog catalog = new ActionCatalog();
        PassAbility ability = new PassAbility();
        catalog.add(ability, "Pass");
        assertEquals(ability, catalog.resolve("act-0"));
    }

    @Test
    public void unknownIdResolvesToNull() {
        ActionCatalog catalog = new ActionCatalog();
        catalog.add(new PassAbility(), "Pass");
        assertNull(catalog.resolve("act-99"));
        assertNull(catalog.resolve("nonsense"));
        assertNull(catalog.resolve(null));
    }

    @Test
    public void idsAreNeverReusedWithinOneCatalog() {
        ActionCatalog catalog = new ActionCatalog();
        for (int i = 0; i < 50; i++) {
            catalog.add(new PassAbility(), "Pass " + i);
        }
        assertEquals(50, catalog.ids().size());
        assertEquals(50, new java.util.HashSet<String>(catalog.ids()).size());
    }

    @Test
    public void twoDistinctAbilitiesGetDistinctIds() {
        ActionCatalog catalog = new ActionCatalog();
        PassAbility a = new PassAbility();
        PassAbility b = new PassAbility();
        catalog.add(a, "A");
        catalog.add(b, "B");
        assertNotEquals(catalog.idFor(a), catalog.idFor(b));
    }

    @Test
    public void labelIsRetrievableById() {
        ActionCatalog catalog = new ActionCatalog();
        catalog.add(new PassAbility(), "Play Mountain");
        assertEquals("Play Mountain", catalog.labelFor("act-0"));
    }

    @Test
    public void idForUnknownAbilityIsNull() {
        ActionCatalog catalog = new ActionCatalog();
        assertNull(catalog.idFor(new PassAbility()));
    }
}
