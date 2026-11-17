package mage.cards.s;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.BecomesTappedSourceTriggeredAbility;
import mage.abilities.condition.Condition;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.watchers.common.PermanentsEnteredBattlefieldWatcher;

import java.util.UUID;

public final class SuluSpaceSwashbuckler extends CardImpl {

    public SuluSpaceSwashbuckler(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.PILOT);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // First strike
        this.addAbility(FirstStrikeAbility.getInstance());

        // Whenever Sulu becomes tapped, if an Equipment or Spacecraft entered the battlefield under your control this turn, draw a card.
        this.addAbility(new BecomesTappedSourceTriggeredAbility(new DrawCardSourceControllerEffect(1))
                .withInterveningIf(SuluSpaceSwashbucklerCondition.instance), new PermanentsEnteredBattlefieldWatcher());
    }

    private SuluSpaceSwashbuckler(final SuluSpaceSwashbuckler card) {
        super(card);
    }

    @Override
    public SuluSpaceSwashbuckler copy() {
        return new SuluSpaceSwashbuckler(this);
    }
}

enum SuluSpaceSwashbucklerCondition implements Condition {
    instance;

    @Override
    public boolean apply(Game game, Ability source) {
        PermanentsEnteredBattlefieldWatcher watcher = game.getState().getWatcher(PermanentsEnteredBattlefieldWatcher.class);
        return watcher != null && watcher.getThisTurnEnteringPermanents(source.getControllerId()).stream()
                .anyMatch(p -> p.hasSubtype(SubType.EQUIPMENT, game) || p.hasSubtype(SubType.SPACECRAFT, game));
    }

    @Override
    public String toString() {
        return "an Equipment or Spacecraft entered the battlefield under your control this turn";
    }
}
