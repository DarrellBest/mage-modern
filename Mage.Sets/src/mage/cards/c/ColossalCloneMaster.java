package mage.cards.c;

import mage.MageInt;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.dynamicvalue.common.DifferentlyNamedPermanentCount;
import mage.abilities.effects.common.CopyPermanentEffect;
import mage.abilities.effects.common.continuous.SetBasePowerToughnessSourceEffect;
import mage.abilities.hint.common.StrangeNewWorldsHint;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AbilityWord;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.util.functions.CopyApplier;

import java.util.UUID;

public final class ColossalCloneMaster extends CardImpl {

    private static final DifferentlyNamedPermanentCount lands
            = new DifferentlyNamedPermanentCount(StaticFilters.FILTER_CONTROLLED_PERMANENT_LANDS);

    private static final CopyApplier applier = new CopyApplier() {
        @Override
        public boolean apply(Game game, MageObject blueprint, Ability source, UUID copyToObjectId) {
            blueprint.removeSuperType(SuperType.LEGENDARY);
            blueprint.getAbilities().add(new SimpleStaticAbility(Zone.ALL, new SetBasePowerToughnessSourceEffect(lands)
                    .setText("this creature's power and toughness are each equal to the number of differently named lands you control")));
            return true;
        }
    };

    public ColossalCloneMaster(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{5}{U}");

        this.subtype.add(SubType.GIANT);
        this.power = new MageInt(0);
        this.toughness = new MageInt(0);

        // Strange new worlds -- You may have this creature enter as a copy of any creature on the battlefield, except
        // it isn't legendary and it has "This creature's power and toughness are each equal to the number of
        // differently named lands you control."
        this.addAbility(new EntersBattlefieldAbility(
                new CopyPermanentEffect(StaticFilters.FILTER_PERMANENT_CREATURE, applier)
                        .setText("as a copy of any creature on the battlefield, except it isn't legendary and it has "
                                + "\"This creature's power and toughness are each equal to the number of differently "
                                + "named lands you control.\""), true
        ).setAbilityWord(AbilityWord.STRANGE_NEW_WORLDS).addHint(StrangeNewWorldsHint.instance));
    }

    private ColossalCloneMaster(final ColossalCloneMaster card) {
        super(card);
    }

    @Override
    public ColossalCloneMaster copy() {
        return new ColossalCloneMaster(this);
    }
}
