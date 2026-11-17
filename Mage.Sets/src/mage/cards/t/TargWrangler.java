package mage.cards.t;

import mage.MageInt;
import mage.abilities.condition.common.AttackedThisTurnSourceCondition;
import mage.abilities.effects.common.SacrificeControllerEffect;
import mage.abilities.keyword.MenaceAbility;
import mage.abilities.triggers.BeginningOfEndStepTriggeredAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.filter.StaticFilters;

import java.util.UUID;

public final class TargWrangler extends CardImpl {

    public TargWrangler(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{R}");

        this.subtype.add(SubType.KLINGON);
        this.subtype.add(SubType.WARRIOR);
        this.power = new MageInt(5);
        this.toughness = new MageInt(3);

        // Menace
        this.addAbility(new MenaceAbility());

        // At the beginning of your end step, if this creature attacked this turn, sacrifice a nonland permanent.
        this.addAbility(new BeginningOfEndStepTriggeredAbility(
                new SacrificeControllerEffect(StaticFilters.FILTER_PERMANENT_NON_LAND, 1, "")
        ).withInterveningIf(AttackedThisTurnSourceCondition.instance));
    }

    private TargWrangler(final TargWrangler card) {
        super(card);
    }

    @Override
    public TargWrangler copy() {
        return new TargWrangler(this);
    }
}
